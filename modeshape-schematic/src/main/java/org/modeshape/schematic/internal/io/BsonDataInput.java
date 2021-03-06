/*
 * ModeShape (http://www.modeshape.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.modeshape.schematic.internal.io;

import java.io.DataInput;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.lang.ref.SoftReference;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;

/**
 * An implementation of {@link DataInput} with additional methods needed for reading BSON formatted content from another DataInput
 * instance. Specifically, this class reads little-endian byte order (purposefully in violation of the DataInput interface
 * specification) and provides a way to read C-style strings (where the length is not known up front but instead contain all
 * characters until the zero-byte terminator), which are commonly used within the BSON specification.
 * 
 * @author Randall Hauch <rhauch@redhat.com> (C) 2011 Red Hat Inc.
 */
public class BsonDataInput implements DataInput {

    /**
     * A thread-local cache of ByteBuffer and CharBuffer instances. Reusing these buffers results in fewer allocations and less to
     * garbage collect. Note that this cache has to be thread-local, since {@link BufferCache} is not thread-safe.
     */
    private final static ThreadLocal<SoftReference<BufferCache>> BUFFER_CACHE = new ThreadLocal<>();

    private static BufferCache getBufferCache() {
        SoftReference<BufferCache> ref = BUFFER_CACHE.get();
        if (ref == null || ref.get() == null) {
            ref = new SoftReference<>(new BufferCache());
            BUFFER_CACHE.set(ref);
        }
        return ref.get();
    }

    private final DataInput input;
    private int total = 0;

    public BsonDataInput( DataInput input ) {
        this.input = input;
    }

    /**
     * Returns the number of bytes that have been read from this input.
     * 
     * @return the total number of bytes already read
     * @exception IOException if an I/O error occurs.
     */
    public int getTotalBytesRead() throws IOException {
        return total;
    }

    @Override
    public final int readUnsignedByte() throws IOException {
        int b1 = input.readUnsignedByte();
        if (b1 == -1) {
            throw new EOFException();
        }
        ++total;
        return (byte)b1;
    }

    @Override
    public boolean readBoolean() throws IOException {
        return readUnsignedByte() != 0;
    }

    @Override
    public byte readByte() throws IOException {
        return (byte)readUnsignedByte();
    }

    @Override
    public char readChar() throws IOException {
        return (char)readUnsignedShort();
    }

    @Override
    public int readUnsignedShort() throws IOException {
        byte b1 = (byte)readUnsignedByte();
        byte b2 = (byte)readUnsignedByte();
        return (b2 & 0xFF) << 8 | (b1 & 0xFF);
    }

    @Override
    public short readShort() throws IOException {
        return (short)readUnsignedShort();
    }

    @Override
    public int readInt() throws IOException {
        byte b1 = (byte)readUnsignedByte();
        byte b2 = (byte)readUnsignedByte();
        byte b3 = (byte)readUnsignedByte();
        byte b4 = (byte)readUnsignedByte();
        return (b4 & 0xFF) << 24 | (b3 & 0xFF) << 16 | (b2 & 0xFF) << 8 | (b1 & 0xFF);
    }

    @Override
    public long readLong() throws IOException {
        byte b1 = (byte)readUnsignedByte();
        byte b2 = (byte)readUnsignedByte();
        byte b3 = (byte)readUnsignedByte();
        byte b4 = (byte)readUnsignedByte();
        byte b5 = (byte)readUnsignedByte();
        byte b6 = (byte)readUnsignedByte();
        byte b7 = (byte)readUnsignedByte();
        byte b8 = (byte)readUnsignedByte();
        return (b8 & 0xFFL) << 56 | (b7 & 0xFFL) << 48 | (b6 & 0xFFL) << 40 | (b5 & 0xFFL) << 32 | (b4 & 0xFFL) << 24
               | (b3 & 0xFFL) << 16 | (b2 & 0xFFL) << 8 | (b1 & 0xFFL);
    }

    @Override
    public float readFloat() throws IOException {
        return Float.intBitsToFloat(readInt());
    }

    @Override
    public double readDouble() throws IOException {
        return Double.longBitsToDouble(readLong());
    }

    @Override
    public void readFully( byte[] b ) throws IOException {
        readFully(b, 0, b.length);
    }

    @Override
    public void readFully( byte[] b,
                           int off,
                           int len ) throws IOException {
        while (len > 0) {
            int read = read(b, off, len);
            if (read < 0) {
                throw new EOFException();
            }
            len -= read;
            off += read;
        }
    }

    protected int read( byte[] b,
                        int off,
                        int len ) throws IOException {
        if (b == null) {
            throw new NullPointerException();
        } else if (off < 0 || len < 0 || len > b.length - off) {
            throw new IndexOutOfBoundsException();
        } else if (len == 0) {
            return 0;
        }

        int i = 0;
        try {
            for ( ; i < len; i++) {
                b[off + i] = read();
            }
        } catch (EOFException ee) {
            return -1;
        }
        return i;
    }

    protected final byte read() throws IOException {
        byte result = input.readByte();
        ++total;
        return result;
    }

    @Override
    public int skipBytes( int n ) throws IOException {
        if (n <= 0) return 0;
        int skipped = input.skipBytes(n);
        total += skipped;
        return skipped;
    }

    @Override
    public String readLine() {
        throw new UnsupportedOperationException();
    }

    @Override
    public String readUTF() throws IOException {
        return DataInputStream.readUTF(this);
    }

    /**
     * Read a UTF-8 string with the supplied length or, if the length is not known, the UTF-8 characters until the next zero-byte
     * value. Note that this is different than the {@link #readUTF() standard way} to read a string, which always expects the
     * length to be the first value on the stream.
     * 
     * @param len the number of bytes to read, or -1 if the length is not known and characters should be read until the next
     *        zero-byte string.
     * @return the read UTF-8 string
     * @throws IOException
     */
    public String readUTF( int len ) throws IOException {
        return readUTF(this, len);
    }

    /**
     * Utility method to read a UTF-8 string from the supplied DataInput object given the supplied number of bytes to read or, if
     * the number of bytes is not known, all of the UTF-8 characters until the next zero-byte value. Note that this is different
     * than the {@link #readUTF() standard way} to read a string, which always expects the length to be the first value on the
     * stream.
     * 
     * @param dis the DataInput from which the UTF-8 string is to be read
     * @param len the number of bytes to read, or -1 if the length is not known and characters should be read until the next
     *        zero-byte string.
     * @return the read UTF-8 string
     * @throws IOException if there is a problem reading from the input
     */
    public static String readUTF( DataInput dis,
                                  int len ) throws IOException {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();
        BufferCache bufferCache = getBufferCache();
        ByteBuffer byteBuf = bufferCache.getByteBuffer(BufferCache.MINIMUM_SIZE);
        CharBuffer charBuf = bufferCache.getCharBuffer(BufferCache.MINIMUM_SIZE);
        try {
            byte[] bytes = byteBuf.array();
            while (len != 0 || byteBuf.position() > 0) {
                // Need to read more bytes ...
                if (len < 0) {
                    // Read until we come across the zero-byte (or until we fill up 'byteBuf') ...
                    while (byteBuf.remaining() != 0) {
                        byte b = dis.readByte();
                        if (b == 0x0) {
                            len = 0; // no more bytes to read ...
                            break;
                        }
                        byteBuf.put(b);
                    }
                    // Prepare byteBuf for reading ...
                    byteBuf.flip();
                } else if (len == 0) {
                    // Don't read anything, but prepare the byteBuf for reading ...
                    byteBuf.flip();
                } else {
                    // We know exactly how much we should read ...
                    int amountToRead = Math.min(len, Math.min(byteBuf.remaining(), charBuf.remaining()));
                    int offset = byteBuf.position(); // may have already read some bytes ...
                    dis.readFully(bytes, offset, amountToRead);
                    // take into account the offset because we might have carry-over bytes from a previous compaction 
                    // this happens when decoding multi-byte UTF8 chars
                    byteBuf.limit(amountToRead + offset); 
                    byteBuf.rewind();
                    // Adjust the number of bytes to read ...
                    len -= amountToRead;
                }

                // We've either read all we need to or as much as we can (given the buffer's limited size),
                // so decode what we've read ...
                boolean endOfInput = len == 0;
                CoderResult result = decoder.decode(byteBuf, charBuf, endOfInput);
                if (result.isError()) {
                    result.throwException();
                } else if (result.isUnderflow()) {
                    // We've not read enough bytes yet, so move the bytes that weren't read to the beginning of the buffer
                    byteBuf.compact();
                    // and try again ...
                }
                if (len > 0 && (charBuf.remaining() == 0 || result.isOverflow())) {
                    // the output buffer was too small or is at its end.
                    // allocate a new one which need to have enough capacity to hold the current one (we're appending buffers) and also to hold
                    // the data from the new iteration.
                    int newBufferIncrement = (len >  BufferCache.MINIMUM_SIZE) ?  BufferCache.MINIMUM_SIZE : len;
                    CharBuffer newBuffer = bufferCache.getCharBuffer(charBuf.capacity() + newBufferIncrement);
                    // prepare the old buffer for reading ...
                    charBuf.flip();
                    // and copy the contents ...
                    newBuffer.put(charBuf);
                    // and use the new buffer ...
                    charBuf = newBuffer;
                }
            }
            // We're done, so prepare the character buffer for reading and then convert to a String ...
            charBuf.flip();
            return charBuf.toString();
        } finally {
            // Return the buffers to the cache ...
            bufferCache.checkin(byteBuf);
            bufferCache.checkin(charBuf);
        }
    }
}
