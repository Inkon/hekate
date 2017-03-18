/*
 * Copyright 2017 The Hekate Project
 *
 * The Hekate Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.hekate.network.internal.netty;

import io.hekate.codec.DataWriter;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import java.io.IOException;
import java.io.OutputStream;

class ByteBufDataWriter extends OutputStream implements DataWriter {
    private static final byte[] LENGTH_PLACEHOLDER = new byte[4];

    private ByteBuf out;

    public ByteBufDataWriter() {
        // No-op.
    }

    public ByteBufDataWriter(ByteBuf out) {
        this.out = out;
    }

    public void setOut(ByteBuf out) {
        this.out = out;
    }

    public ByteBuf buffer() {
        return out;
    }

    @Override
    public OutputStream asStream() {
        return this;
    }

    @Override
    public void writeBoolean(boolean v) throws IOException {
        out.writeBoolean(v);
    }

    @Override
    public void writeByte(int v) throws IOException {
        out.writeByte(v);
    }

    @Override
    public void writeShort(int v) throws IOException {
        out.writeShort(v);
    }

    @Override
    public void writeChar(int v) throws IOException {
        out.writeChar(v);
    }

    @Override
    public void writeInt(int v) throws IOException {
        out.writeInt(v);
    }

    @Override
    public void writeLong(long v) throws IOException {
        out.writeLong(v);
    }

    @Override
    public void writeFloat(float v) throws IOException {
        writeInt(Float.floatToIntBits(v));
    }

    @Override
    public void writeDouble(double v) throws IOException {
        writeLong(Double.doubleToLongBits(v));
    }

    @Override
    public void writeBytes(String s) throws IOException {
        int len = s.length();

        for (int i = 0; i < len; i++) {
            write((byte)s.charAt(i));
        }
    }

    @Override
    public void writeChars(String s) throws IOException {
        int len = s.length();

        for (int i = 0; i < len; i++) {
            writeChar(s.charAt(i));
        }
    }

    @Override
    public void writeUTF(String str) throws IOException {
        int lenPos = out.writerIndex();

        out.writeBytes(LENGTH_PLACEHOLDER);

        int len = ByteBufUtil.writeUtf8(out, str);

        out.setInt(lenPos, len);
    }

    @Override
    public void write(int b) throws IOException {
        out.writeByte(b);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        out.writeBytes(b, off, len);
    }
}