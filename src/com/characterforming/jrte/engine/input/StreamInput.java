/**
 * Copyright (c) 2011,2017, Kim T Briggs, Hampton, NB.
 */
package com.characterforming.jrte.engine.input;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;

import com.characterforming.jrte.InputException;
import com.characterforming.jrte.base.BaseInput;

/**
 * @author kb
 */
public class StreamInput extends BaseInput {
	private final InputStream stream;
	private final CharsetDecoder decoder;
	private final ByteBuffer bytes;
	private boolean eof;

	/**
	 * Constructor
	 * 
	 * @throws InputException
	 */
	public StreamInput(final InputStream input, final Charset charset) throws InputException {
		super(4, 4096);
		this.stream = input;
		this.decoder = charset.newDecoder();
		this.bytes = ByteBuffer.wrap(new byte[4096]);
		this.bytes.limit(0);
		this.eof = false;
	}

	/*
	 * (non-Javadoc)
	 * @see com.characterforming.jrte.base.BaseInput#next()
	 */
	@Override
	public CharBuffer next(final CharBuffer empty) throws InputException {
		if (!this.eof) {
			try {
				final byte[] buffer = this.bytes.array();
				int count = this.bytes.capacity() - this.bytes.limit();
				if (0 < count) {
					count = this.stream.read(buffer, this.bytes.limit(), count);
					this.bytes.limit(this.bytes.limit() + count);
				}
				if (this.bytes.position() < this.bytes.limit()) {
					this.decoder.decode(this.bytes, empty, false);
					if (this.bytes.remaining() == 0) {
						this.bytes.position(0);
						this.bytes.limit(0);
					}
					return (CharBuffer) empty.flip();
				} else if (this.bytes.remaining() == 0) {
					this.bytes.position(0);
					this.bytes.limit(0);
					this.eof = true;
					return null;
				} else {
					throw new InputException(String.format("%1$d undecoded bytes remaining in at end of stream", this.bytes.remaining()));
				}
			} catch (final IOException e) {
				throw new InputException("Caught an IOException reading from InputStream", e);
			}
		} else {
			return null;
		}
	}
}
