package da.exercises.googledataflow;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackInputStream;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.BitSet;

import org.apache.flink.hadoop.shaded.com.google.common.base.Splitter;
import org.apache.flink.shaded.com.google.common.base.Joiner;
import org.apache.flink.shaded.com.google.common.collect.Iterables;

import da.exercises.googledataflow.CsvSource.Util;

class CsvToXmlReadableByteChannel implements ReadableByteChannel {

    private static final int SIZE = 8 * 1024;
    private static final String CHARSET = StandardCharsets.UTF_8.name();

    private final ReadableByteChannel delegate;
    private PushbackInputStream stream;
    private ByteArrayOutputStream overall = new ByteArrayOutputStream(SIZE);
    private ByteArrayOutputStream recordStash = new ByteArrayOutputStream(SIZE);
    private ByteArrayOutputStream spilloverStash = new ByteArrayOutputStream(SIZE);

    private byte[] fieldDelimiter;
    private byte[] lineDelimiter;
    private String[] fields;
    private BitSet includeFields;
    private String recordName = "row";

    private boolean foundRecordBoundary = false;
    private boolean available = true;
    private int includedFieldIdx = 0;
    private int fieldIdx = 0;
    private int recordDelimIdx = 0;
    private int fieldDelimIdx = 0;
    private long currentOffset;
    private boolean epilogWithRootWritten;
    // private byte[] buffer = new byte[SIZE];
    private ByteBuffer buffer = ByteBuffer.allocate(SIZE);
    private ReadableByteChannel pushBackChannel;

    public CsvToXmlReadableByteChannel(ReadableByteChannel delegate) {
        this.delegate = delegate;
        this.stream = new PushbackInputStream(Channels.newInputStream(delegate), SIZE);
        this.pushBackChannel = Channels.newChannel(stream);
    }

    private void reset() {
        recordStash.reset();
        spilloverStash.reset();

        foundRecordBoundary = false;
        fieldIdx = 0;
        includedFieldIdx = 0;
        recordDelimIdx = 0;
        fieldDelimIdx = 0;
    }

    private void processField(int fieldBoundaryIdx, int prevFieldBoundaryIndex) throws IOException {
        if (includeFields.get(fieldIdx)) {
            int len = foundRecordBoundary ? lineDelimiter.length : fieldDelimiter.length;
            // <![CDATA[]]
            write(recordStash, ("<" + fields[includedFieldIdx] + "><![CDATA["));

            int cut = 0;
            if (spilloverStash.size() > 0) {
                if (fieldBoundaryIdx < len) {
                    cut = (len - fieldBoundaryIdx);
                    len -= cut;
                }
                byte[] spilledBytes = spilloverStash.toByteArray();
                recordStash.write(spilledBytes, 0, spilledBytes.length - cut);
            }

            recordStash.write(buffer.array(), prevFieldBoundaryIndex, fieldBoundaryIdx - prevFieldBoundaryIndex - len);
            write(recordStash, ("]]></" + fields[includedFieldIdx] + ">"));
            includedFieldIdx++;
        }

        spilloverStash.reset();
        fieldDelimIdx = 0;
        fieldIdx++;

    }

    private void processUntilNextRecordBoundary() throws IOException {
        reset();

        if (!epilogWithRootWritten) {
            write(overall, "<?xml version=\"1.0\" encoding=\"" + CHARSET + "\"?><" + recordName + "s>");
            epilogWithRootWritten = true;
        }

        int fieldBoundaryIdx = 0;

        while (!foundRecordBoundary && available) {
            buffer.clear();
            int read = pushBackChannel.read(buffer);
            if (read != -1) {
                buffer.flip();
                int prevFieldBoundaryIndex = 0;
                while (buffer.hasRemaining()) {
                    byte b = buffer.get();

                    if (b == fieldDelimiter[fieldDelimIdx]) {
                        fieldDelimIdx++;
                        if (fieldDelimIdx == fieldDelimiter.length) {
                            // found field
                            fieldBoundaryIdx = buffer.position();
                            processField(fieldBoundaryIdx, prevFieldBoundaryIndex);
                            prevFieldBoundaryIndex = fieldBoundaryIdx;
                        }
                    } else {
                        fieldDelimIdx = 0;
                    }

                    if (b == lineDelimiter[recordDelimIdx]) {
                        recordDelimIdx++;
                        if (recordDelimIdx == lineDelimiter.length) {
                            foundRecordBoundary = true;
                            fieldBoundaryIdx = buffer.position();
                            currentOffset += fieldBoundaryIdx;
                            processField(fieldBoundaryIdx, prevFieldBoundaryIndex);
                            stream.unread(buffer.array(), fieldBoundaryIdx, buffer.remaining());
                            break;
                        }
                    } else {
                        recordDelimIdx = 0;
                    }
                }

                if (!foundRecordBoundary) {
                    currentOffset += read;
                    spilloverStash.write(buffer.array(), fieldBoundaryIdx, read - fieldBoundaryIdx);
                }
            } else {
                available = false;
                if (!foundRecordBoundary && includedFieldIdx > 0) {
                    foundRecordBoundary = true;
                    processField(fieldBoundaryIdx + lineDelimiter.length, fieldBoundaryIdx); // already
                    // stashed
                }
            }
        }

        if (includedFieldIdx == fields.length) {
            write(overall, "<" + recordName + ">");
            write(recordStash, overall);
            write(overall, "</" + recordName + ">");
        }
    }

    private void write(ByteArrayOutputStream os, String s) throws IOException {
        os.write(s.getBytes(CHARSET));
    }

    private void write(ByteArrayOutputStream from, ByteArrayOutputStream to) throws IOException {
        from.writeTo(to);
    }

    public CsvToXmlReadableByteChannel withFieldDelimiter(String fieldDelimiter) {
        try {
            this.fieldDelimiter = fieldDelimiter.getBytes(CHARSET);
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
        return this;
    }

    public CsvToXmlReadableByteChannel withIncludeFields(String includeFields) {
        if (includeFields != null) {
            this.includeFields = CsvSource.Util.toBitSet(includeFields);
        }
        return this;
    }

    public CsvToXmlReadableByteChannel withLineDelimiter(String lineDelimiter) {
        try {
            this.lineDelimiter = lineDelimiter.getBytes(CHARSET);
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
        return this;
    }

    public CsvToXmlReadableByteChannel withRecordName(String name) {
        this.recordName = name;
        return this;
    }

    public CsvToXmlReadableByteChannel withFields(String... fields) {
        this.fields = fields;
        if (this.includeFields == null) {
            withIncludeFields(Util.stringOfAllOnes(fields.length));
        }
        return this;
    }

    @Override
    public void close() throws IOException {
        pushBackChannel.close();
        stream.close();
        delegate.close();
        overall = null;
        recordStash = null;
        spilloverStash = null;
        buffer = null;
    }

    @Override
    public boolean isOpen() {
        return delegate.isOpen();
    }

    @Override
    public int read(ByteBuffer dst) throws IOException {
        int remaining = dst.remaining();

        if (available) {
            while (overall.size() < remaining) {
                if (!available) {
                    write(overall, "</" + recordName + "s>");
                    break;
                }
                processUntilNextRecordBoundary();
            }
        }

        int writtenSize = -1;

        byte[] buf = overall.toByteArray();
        int len = buf.length;
        if (len > 0) {
            writtenSize = len > remaining ? remaining : len;

            dst.clear();
            dst.put(buf, 0, writtenSize);

            overall.reset();
            if (len > writtenSize) {
                overall.write(buf, writtenSize, len - writtenSize);
            }
        }

        return writtenSize;
    }

    public long getCurrentOffset() {
        return currentOffset;
    }

    public static void main(String[] args) throws Exception {
        URL url = Thread.currentThread().getContextClassLoader().getResource("flinkMails");
        try (InputStream is = url.openStream()) {
            ReadableByteChannel delegate = Channels.newChannel(is);

            //@formatter:off
            try (CsvToXmlReadableByteChannel channel = new CsvToXmlReadableByteChannel(delegate)
                                                           .withFieldDelimiter("#|#")
                                                           .withLineDelimiter("##//##")
                                                           .withFields("f0", "f3", "f5")
                                                           .withIncludeFields("100101")
                                                           .withRecordName("mail")) {
                //@formatter:on

                Iterable<String> paths = Splitter.on('/').split(url.getPath());
                String output = (Joiner.on('/').join(Iterables.limit(paths, Iterables.size(paths) - 1)) + "/flinkMailsOutput").replaceFirst("^/(.:/)",
                        "$1");

                BufferedReader br = new BufferedReader(new InputStreamReader(Channels.newInputStream(channel)));
                Writer writer = Channels.newWriter(Files.newByteChannel(Paths.get(output), StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING), StandardCharsets.UTF_8.name());

                String line = null;
                while ((line = br.readLine()) != null) {
                    writer.write(line);
                }
                writer.flush();
                writer.close();

                br.close();
            }
        }
    }

}
