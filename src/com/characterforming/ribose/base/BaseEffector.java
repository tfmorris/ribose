/***
 * Ribose is a recursive transduction engine for Java
 * 
 * Copyright (C) 2011,2022 Kim Briggs
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program (LICENSE-gpl-3.0). If not, see
 * <http://www.gnu.org/licenses/#GPL>.
 */

package com.characterforming.ribose.base;

import com.characterforming.jrte.engine.Base;
import com.characterforming.ribose.IEffector;
import com.characterforming.ribose.IOutput;
import com.characterforming.ribose.ITarget;

/**
 * Base {@link IEffector} implementation class provides subclasses with access
 * to the transduction target and an output view to enable named value extraction. 
 * The {@link invoke()} method must be implemented by subclasses.
 * 
 * @param <T> The effector target type
 * @author Kim Briggs
 */
public abstract class BaseEffector<T extends ITarget> implements IEffector<T> {

	/** Effector access to the target that it is bound to */
	protected final T target;
	/** Effector view of Transductor loggers, UTF-8 codecs and named values. */
	protected IOutput output;

	private final Bytes name;
	
	/**
	 * Constructor receivs target and a name.
	 * 
	 * @param target The target that binds the effector
	 * @param name The effector name
	 */
	public BaseEffector(final T target, final String name) {
		this.name = Bytes.encode(Base.newCharsetEncoder(), name);
		this.target = target;
		this.output = null;
	}
	
	@Override // @see com.characterforming.ribose.base.IEffector#setOutput(IOutput)
	public void setOutput(IOutput output) throws TargetBindingException {
		this.output = output;
	}

	@Override // @see com.characterforming.ribose.base.IEffector#getName()
	public final Bytes getName() {
		return this.name;
	}

	@Override // @see com.characterforming.ribose.base.IEffector#getTarget()
	public final T getTarget() {
		return this.target;
	}

	@Override // @see com.characterforming.ribose.base.IEffector#invoke()
	public abstract int invoke() throws EffectorException;

	@Override // @see java.lang.Object#toString()
	public String toString() {
		return this.name.toString();
	}
}
