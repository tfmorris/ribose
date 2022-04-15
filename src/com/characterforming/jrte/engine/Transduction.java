/***
 * JRTE is a recursive transduction engine for Java
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
 * You should have received copies of the GNU General Public License
 * and GNU Lesser Public License along with this program.  See 
 * LICENSE-lgpl-3.0 and LICENSE-gpl-3.0. If not, see 
 * <http://www.gnu.org/licenses/>.
 */

package com.characterforming.jrte.engine;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Map;
import java.util.Map.Entry;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.characterforming.jrte.ByteInput;
import com.characterforming.jrte.DomainErrorException;
import com.characterforming.jrte.EffectorException;
import com.characterforming.jrte.IEffector;
import com.characterforming.jrte.IInput;
import com.characterforming.jrte.INamedValue;
import com.characterforming.jrte.IOutput;
import com.characterforming.jrte.IParameterizedEffector;
import com.characterforming.jrte.ITarget;
import com.characterforming.jrte.ITransduction;
import com.characterforming.jrte.InputException;
import com.characterforming.jrte.ModelException;
import com.characterforming.jrte.RteException;
import com.characterforming.jrte.TargetBindingException;
import com.characterforming.jrte.base.Base;
import com.characterforming.jrte.base.BaseEffector;
import com.characterforming.jrte.base.BaseParameterizedEffector;
import com.characterforming.jrte.base.Bytes;

/**
 * Runtime transduction instances are instantiated using {@link IRiboserRuntime#newTransaction(ITarget)}.
 * Client applications drive the transduction using the Transduction.run() method, 
 * which processes the bound IInput stack until one of the following conditions is 
 * satisfied: <br>
 * <ol>
 * <li>the input stack is empty
 * <li>the transducer stack is empty
 * <li>an effector returns RTE_EFFECT_PAUSE
 * <li>the transduction throws an exception.
 * </ol>
 * 
 * @author Kim Briggs
 */
public final class Transduction implements ITransduction, ITarget, IOutput {
	private static final boolean isOutEnabled = System.getProperty("jrte.out.enabled", "true").equals("true");
	private final static Logger logger = Logger.getLogger(Base.RTE_LOGGER_NAME);

	static int INITIAL_NAMED_VALUE_BUFFERS = 256;
	static int INITIAL_NAMED_VALUE_BYTES = 256;

	public static final int RTE_EFFECTOR_NUL = 0;
	public static final int RTE_EFFECTOR_NIL = 1;
	public static final int RTE_EFFECTOR_PASTE = 2;
	public static final int RTE_EFFECTOR_SELECT = 3;
	public static final int RTE_EFFECTOR_COPY = 4;
	public static final int RTE_EFFECTOR_CUT = 5;
	public static final int RTE_EFFECTOR_CLEAR = 6;
	public static final int RTE_EFFECTOR_COUNT = 7;
	public static final int RTE_EFFECTOR_IN = 8;
	public static final int RTE_EFFECTOR_OUT = 9;
	public static final int RTE_EFFECTOR_MARK = 10;
	public static final int RTE_EFFECTOR_RESET = 11;
	public static final int RTE_EFFECTOR_START = 12;
	public static final int RTE_EFFECTOR_PAUSE = 13;
	public static final int RTE_EFFECTOR_STOP = 14;

	private final Model model;
	private IEffector<?>[] effectors;
	private NamedValue[] namedValueHandles;
	private Map<Bytes, Integer> namedValueOrdinalMap;
	private InputStack inputStack;
	private TransducerStack transducerStack;
	private NamedValue selected;
	private int errorCount;
	private int effect;
	
	/**
	 *  Constructor
	 *
	 * @param model The runtime model 
	 */
	Transduction(final Model model) {
		super();
		this.model = model;
		this.effectors = null;
		this.namedValueHandles = null;
		this.namedValueOrdinalMap = null;
		this.inputStack = null;
		this.transducerStack = null;
		this.selected = null;
		this.errorCount = 0;
		this.effect = RTE_EFFECTOR_NUL;
	}

	void setEffectors(IEffector<?>[] effectors) {
		this.effectors = effectors;
	}

	void setNamedValueOrdinalMap(Map<Bytes, Integer> namedValueOrdinalMap) {
		this.namedValueOrdinalMap = namedValueOrdinalMap;
		if (this.namedValueOrdinalMap.size() > 0) {
			this.namedValueHandles = new NamedValue[this.namedValueOrdinalMap.size()];
			for (final Entry<Bytes, Integer> entry : this.namedValueOrdinalMap.entrySet()) {
				final int valueIndex = entry.getValue();
				byte[] valueBuffer = new byte[INITIAL_NAMED_VALUE_BYTES];
				this.namedValueHandles[valueIndex] = new NamedValue(entry.getKey(), valueIndex, valueBuffer, 0);
			}
			this.selected = this.namedValueHandles[Base.ANONYMOUS_VALUE_ORDINAL];
		}
	}

	/*
	 * (non-Javadoc)
	 * @see com.characterforming.jrte.engine.ITarget#bindEffectors(int)
	 */
	@Override
	public IEffector<?>[] bindEffectors() throws TargetBindingException {
		return new IEffector<?>[] {
	/* 0*/	new InlineEffector(this, Bytes.encode("0")),
	/* 1*/	new InlineEffector(this, Bytes.encode("1")),
	/* 2*/	new PasteEffector(this),
	/* 3*/	new SelectEffector(this),
	/* 4*/	new CopyEffector(this),
	/* 5*/	new CutEffector(this),
	/* 6*/	new ClearEffector(this),
	/* 7*/	new CountEffector(this),
	/* 8*/	new InEffector(this),
	/* 9*/	new OutEffector(this),
	/*10*/	new InlineEffector(this, Bytes.encode("mark")),
	/*11*/	new InlineEffector(this, Bytes.encode("reset")),
	/*12*/	new StartEffector(this),
	/*13*/	new PauseEffector(this),
	/*14*/	new StopEffector(this)
		};
	}

	@Override
	public String getName() {
		return this.getClass().getSimpleName();
	}

	/*
	 * (non-Javadoc)
	 * @see com.characterforming.jrte.engine.ITransduction#status()
	 */
	@Override
	public Status status() {
		if (this.effectors != null) {
			if ((this.transducerStack == null) || (this.transducerStack.isEmpty())) {
				return ITransduction.Status.STOPPED;
			} else if (this.inputStack != null && !this.inputStack.isEmpty()) {
				return ITransduction.Status.RUNNABLE;
			} else {
				return ITransduction.Status.PAUSED;			
			}
		} else {
			return ITransduction.Status.NULL;
		}
	}

	/*
	 * (non-Javadoc)
	 * @see com.characterforming.jrte.engine.ITransduction#input(IInput[])
	 */
	@Override
	public Status input(final IInput[] inputs) {
		if (this.status() == ITransduction.Status.NULL) {
			logger.log(Level.SEVERE, "Transduction not bound to target");
			return this.status();
		}
		if (this.inputStack == null) {
			this.inputStack = new InputStack(inputs.length);
		}
		for (IInput input : inputs) {
			this.inputStack.push(input);
		}
		return this.status();
	}

	/*
	 * (non-Javadoc)
	 * @see com.characterforming.jrte.engine.ITransduction#start(String)
	 */
	@Override
	public Status start(final Bytes transducerName) throws ModelException {
		if (this.status() != ITransduction.Status.NULL) {
			int transducerOrdinal = this.model.getTransducerOrdinal(transducerName);
			Transducer transducer = null;
			transducer = this.model.loadTransducer(transducerOrdinal);
			if (transducer != null) {
				if (this.transducerStack == null) {
					this.transducerStack = new TransducerStack(8);
				}
				this.transducerStack.push(transducer);
				this.select(Base.ANONYMOUS_VALUE_ORDINAL);
				this.clear();
			} else {
				logger.log(Level.SEVERE, String.format("No transducer named %1$s", 
						transducerName.toString()));
			}
		} else {
			logger.log(Level.SEVERE, "Transduction not bound to target");
		}
		return this.status();
	}

	/*
	 * (non-Javadoc)
	 * @see com.characterforming.jrte.engine.ITransduction#stop()
	 */
	@Override
	public Status stop() {
		if (this.inputStack != null) {
			while (!this.inputStack.isEmpty()) {
				this.inputStack.pop();
			}
			this.inputStack = null;
		}
		if (this.transducerStack != null) {
			while (!this.transducerStack.isEmpty()) {
				this.transducerStack.pop();
			}
			this.transducerStack = null;
		}
		for (NamedValue value : this.namedValueHandles) {
			value.clear();
		}
		this.selected = this.namedValueHandles[Base.ANONYMOUS_VALUE_ORDINAL];
		return this.status();
	}

	/*
	 * (non-Javadoc)
	 * @see com.characterforming.jrte.engine.ITransduction#run()
	 */
	@Override
	public Status run() throws RteException, DomainErrorException {
		if (this.status() == ITransduction.Status.NULL) {
			RteException rtx = new RteException("run: Transaction is MODEL and inoperable");
			logger.log(Level.SEVERE, rtx.getMessage(), rtx);
			throw rtx;
		}
		final int nulSignal = Base.Signal.nul.signal();
		final int eosSignal = Base.Signal.eos.signal();
		int errorCount = this.errorCount = 0;
		ByteBuffer inputBuffer = null;
		int position = 0, limit = 0;
		int state = 0;
		try {
T:		while (this.status() == ITransduction.Status.RUNNABLE) {
				final TransducerState transducerState = this.transducerStack.peek();
				final Transducer transducer = transducerState.transducer;
				final int[] inputFilter = transducer.getInputFilter();
				final int[][] transitionMatrix = transducer.getTransitionMatrix();
				final int[] effectorVector = transducer.getEffectorVector();
				
				state = transducerState.state;
				inputBuffer = null;
				
				int errorInput = -1;
				int signalInput = -1;
				int currentInput = -1;
				byte[] inputArray = null;
				ITransduction.Status status = this.status();
				while (status == ITransduction.Status.RUNNABLE) {
					
					// Get next input symbol from local array, or try to refresh it if exhausted
					if (signalInput >= 0) {
						currentInput = signalInput;
						signalInput = -1;
					} else if (position < limit) {
						currentInput = Byte.toUnsignedInt(inputArray[position++]);
					} else {
						IInput input = this.inputStack.peek();
						while (input != null) {
							inputBuffer = input.get();
							if (inputBuffer != null) {
								assert inputBuffer.hasArray();
								limit = inputBuffer.limit();
								inputArray = inputBuffer.array();
								if (Base.getReferenceType(inputArray) == Base.TYPE_REFERENCE_SIGNAL) {
									currentInput = Base.decodeReferenceOrdinal(Base.TYPE_REFERENCE_SIGNAL, inputArray);
									inputBuffer.position(limit);
									position = limit;
									assert currentInput >= 0;
								} else if (Base.getReferenceType(inputArray) == Base.TYPE_REFERENCE_VALUE) {
									int valueOrdinal = Base.decodeReferenceOrdinal(Base.TYPE_REFERENCE_VALUE, inputArray);
									inputBuffer.position(limit);
									inputBuffer = ByteBuffer.wrap(this.namedValueHandles[valueOrdinal].getValue(), 0, this.namedValueHandles[valueOrdinal].getLength());
									ByteInput valueInput = new ByteInput(new ByteBuffer[] {inputBuffer});
									this.inputStack.push(valueInput);
									input = this.inputStack.peek();
									inputBuffer = input.get();
									limit = inputBuffer.limit();
									inputArray = inputBuffer.array();
									position = inputBuffer.position();
									currentInput = Byte.toUnsignedInt(inputArray[position++]);
									assert currentInput >= 0;
								} else {
									assert !Base.isReferenceOrdinal(inputArray);
									position = inputBuffer.position();
									currentInput = Byte.toUnsignedInt(inputArray[position++]);
								}
								break;
							}
							input = this.inputStack.pop();
						}
						if (input == null) {
							inputBuffer = null;
							if ((currentInput != eosSignal) && (currentInput != nulSignal)) {
								currentInput = eosSignal;
							} else {
								break T;
							} 
						} 
					}
					
					int action = RTE_EFFECTOR_NUL;
					int index = 0;
I:				do {
						// Filter input to equivalence ordinal and map ordinal and state to next state and action
						final int transition = state + inputFilter[currentInput];
						state = transitionMatrix[transition][0];
						action = transitionMatrix[transition][1];
						switch (action) {
						case RTE_EFFECTOR_NIL:
							break;
						case RTE_EFFECTOR_PASTE:
							this.selected.append((byte)currentInput);
							break;
						default:
							if (action < RTE_EFFECTOR_NUL) {
								index = -action;
								action = effectorVector[index++];
							} 
							break I;
						}
						if (position < limit) {
							currentInput = inputArray[position++];
						} else {
							action = RTE_EFFECTOR_NIL;
						}
					} while (position < limit);
					
					// Invoke a vector of 1 or more effectors and record side effects on transduction and input stacks 
					effect = IEffector.RTE_EFFECT_NONE;
					do {
						switch (action) {
						default:
							if (action > 0) {
								effect |= this.effectors[action].invoke();
							} else {
								assert this.effectors[(-1)*action] instanceof IParameterizedEffector<?,?>;
								effect |= ((IParameterizedEffector<?,?>)this.effectors[(-1)*action]).invoke(effectorVector[index++]);
							}
							break;
						case RTE_EFFECTOR_NUL:
							if (currentInput == nulSignal) {
								String debugTrace = this.getErrorInput(state, errorInput);
								int debugState = state / this.transducerStack.peek().transducer.getInputEquivalentsCount();
								throw new DomainErrorException(String.format("Domain error on [%1$d] in %2$s (state %3$d) %4$s", 
									errorInput, transducer.getName(), debugState,	debugTrace));
							} else if (currentInput != eosSignal) {
								inputBuffer.position(position);
								errorInput = currentInput;
								signalInput = nulSignal;
								++errorCount;
							}
							break;
						case RTE_EFFECTOR_NIL:
							break;
						case RTE_EFFECTOR_PASTE:
							assert currentInput >= 0 && currentInput <= 0xff;
							this.selected.append((byte)currentInput);
							break;
						case RTE_EFFECTOR_SELECT:
							this.selected = this.namedValueHandles[Base.ANONYMOUS_VALUE_ORDINAL];
							assert this.selected != null;
							break;
						case RTE_EFFECTOR_COPY:
							this.selected.append(this.namedValueHandles[Base.ANONYMOUS_VALUE_ORDINAL]);
							break;
						case RTE_EFFECTOR_CUT:
							this.selected.append(this.namedValueHandles[Base.ANONYMOUS_VALUE_ORDINAL]);
							this.namedValueHandles[Base.ANONYMOUS_VALUE_ORDINAL].clear();
							break;
						case RTE_EFFECTOR_CLEAR:
							this.selected.clear();
							break;
						case RTE_EFFECTOR_COUNT:
							if (--transducerState.countdown[0] <= 0) {
								effect |= this.in(transducerState.countdown[1]);
								transducerState.countdown[0] = 0;
							}
							break;
						case RTE_EFFECTOR_IN:
							this.inputStack.push(new ByteInput(copyNamedValue()));
							effect |=  IEffector.RTE_EFFECT_PUSH;
							break;
						case RTE_EFFECTOR_OUT: {
							if (Transduction.isOutEnabled && this.selected.getLength() > 0) {
								System.out.print(Charset.defaultCharset().decode(ByteBuffer.wrap(selected.getValue(), 0, selected.getLength())).toString());
								System.out.flush();
							}
							break;
						}
						case RTE_EFFECTOR_MARK:
							inputBuffer.position(position);
							IInput input = inputStack.peek();
							while (inputBuffer != null && !inputBuffer.hasRemaining()) {
								inputBuffer = input.get();
								if (inputBuffer == null) {
									input = this.inputStack.pop();
									if (input != null) {
										inputBuffer = input.current();
									}
								}
							}
							if (inputBuffer != null) {
								assert !Base.isReferenceOrdinal(inputBuffer.array());
								assert inputBuffer.hasRemaining();
								inputArray = inputBuffer.array();
								position = inputBuffer.position();
								limit = inputBuffer.limit();
								input.mark();
							} else {
								effect |= IEffector.RTE_EFFECT_POP;
							}
							break;
						case RTE_EFFECTOR_RESET:
							inputBuffer.position(position);
							assert !this.inputStack.isEmpty();
							assert !Base.isReferenceOrdinal(inputStack.peek().current().array());
							ByteBuffer[] buffers = this.inputStack.peek().reset();
							if (buffers != null) {
								assert buffers.length > 0;
								this.inputStack.push(new ByteInput(buffers));
								effect |= IEffector.RTE_EFFECT_PUSH;
							} else {
								position = inputBuffer.position();
							}
							break;
						case RTE_EFFECTOR_PAUSE:
							effect |= IEffector.RTE_EFFECT_PAUSE;
							break;
						case RTE_EFFECTOR_STOP:
							effect |= popTransducer();
							break;
						}
						assert index >= 0;
						action = effectorVector[index++];
					} while (action != RTE_EFFECTOR_NUL);
					
					if ((position >= limit) || (effect != IEffector.RTE_EFFECT_NONE)) {
						// Synchronize position with input buffer at limit and when input/transducer stacks are modified 
						if (inputBuffer != null) {
							inputBuffer.position(position);
						}
						
						// Handle side effects relating to transduction status (input/transduction stacks)
						if (effect != IEffector.RTE_EFFECT_NONE) {
							status = this.status();
							
							// Input pushed, popped, marked or reset, or transducer stack pushed, popped?
							if (0 != (effect & (IEffector.RTE_EFFECT_PUSH | IEffector.RTE_EFFECT_POP))) {
								limit = -1; 
							}
							
							if (0 != (effect & (IEffector.RTE_EFFECT_START | IEffector.RTE_EFFECT_STOP))) {
								if (0 != (effect & IEffector.RTE_EFFECT_START)) {
									this.transducerStack.peek(this.transducerStack.tos() - 1).state = state;
								}
								limit = -1; 
								break;
							}
							
							// Transduction paused or stopped?
							if (effect >= IEffector.RTE_EFFECT_PAUSE) {
								break T;
							} 
						}
					}
										
					// Check for input that might have been pushed while handling end of stream signal
					if (currentInput == eosSignal) {
						status = this.status();
					}
				}
			}
		} catch (Exception e) {
			RteException rtx = new RteException("Unexpected exception in run()", e);
			logger.log(Level.SEVERE, rtx.getMessage(), rtx);
			throw rtx;
		} finally {
			
			// Prepare to pause (or stop) transduction
			if (!this.transducerStack.isEmpty()) {
				this.transducerStack.peek().state = state;
			}
			if (inputBuffer != null) {
				if (position > inputBuffer.limit()) {
					assert (position == (inputBuffer.limit() + 1) && Base.getReferenceType(inputBuffer.array()) == Base.TYPE_REFERENCE_SIGNAL);
					position = inputBuffer.limit();
				}
				inputBuffer.position(position);
			}
		}
		
		// Transduction is paused or stopped; if paused it will resume on next call to run()
		this.errorCount = errorCount;
		return this.status();
	}

	@Override
	public int getErrorCount() {
		return this.errorCount;
	}

	/*
	 * (non-Javadoc)
	 * @see com.characterforming.jrte.engine.IOutput#getValueOrdinal(Bytes)
	 */
	@Override
	public int getValueOrdinal(final Bytes valueName) {
		assert this.namedValueHandles != null;
		if (this.namedValueHandles != null
		&& this.namedValueOrdinalMap.containsKey(valueName)) {
			return this.namedValueOrdinalMap.get(valueName);
		}
		return -1;
	}

	/*
	 * (non-Javadoc)
	 * @see com.characterforming.jrte.engine.IOutput#getSelectedOrdinal()
	 */
	@Override
	public int getSelectedOrdinal() {
		assert this.selected != null;
		return (this.selected != null) ? this.selected.getOrdinal() : -1;
	}

	/*
	 * (non-Javadoc)
	 * @see com.characterforming.jrte.engine.IOutput#getNamedValue(int)
	 */
	@Override
	public INamedValue getNamedValue(final int nameOrdinal) {
		assert this.namedValueHandles != null;
		if (this.namedValueHandles != null && nameOrdinal < this.namedValueHandles.length) {
			return this.namedValueHandles[nameOrdinal];
		} else {
			return null;
		}
	}

	/*
	 * (non-Javadoc)
	 * @see com.characterforming.jrte.engine.IOutput#getNamedValue(String)
	 */
	@Override
	public INamedValue getNamedValue(final String valueName) {
		return this.getNamedValue(this.getValueOrdinal(Bytes.encode(valueName)));
	}

	/*
	 * (non-Javadoc)
	 * @see com.characterforming.jrte.engine.IOutput#getSelectedValue()
	 */
	@Override
	public INamedValue getSelectedValue() {
		assert this.selected != null;
		return (this.selected != null) ? new NamedValue(this.selected) : null;
	}

	/*
	 * (non-Javadoc)
	 * @see com.characterforming.jrte.engine.ITransduction#getModel()
	 */
	Model getModel() {
		return this.model;
	}

	private int select(final int selectionIndex) {
		this.selected = this.namedValueHandles[selectionIndex];
		return IEffector.RTE_EFFECT_NONE;
	}

	private int paste(final byte[] bytes, int start, int length) {
		this.selected.append(bytes);
		return IEffector.RTE_EFFECT_NONE;
	}

	private int copy(final int nameIndex) {
		this.selected.append(this.namedValueHandles[nameIndex]);
		return IEffector.RTE_EFFECT_NONE;
	}

	private int cut(final int nameIndex) {
		this.copy(nameIndex);
		this.namedValueHandles[nameIndex].clear();
		return IEffector.RTE_EFFECT_NONE;
	}

	private int clear(final int nameIndex) {
		assert (nameIndex >= 0) || (nameIndex == -1);
		int index = (nameIndex >= 0) ? nameIndex : this.selected.getOrdinal();
		if (index != Base.CLEAR_VALUE_ORDINAL) {
			this.namedValueHandles[index].clear();
		} else {
			clear();
		}
		return IEffector.RTE_EFFECT_NONE;
	}

	private int clear() {
		for (NamedValue nv : this.namedValueHandles) {
			nv.clear();
		}
		return IEffector.RTE_EFFECT_NONE;
	}

	private int count(final int[] countdown) throws InputException {
		assert countdown.length == 2;
		TransducerState tos = this.transducerStack.peek();
		System.arraycopy(countdown, 0, tos.countdown, 0, countdown.length);
		if (tos.countdown[0] < 0) {
			tos.countdown[0] = (int)this.getNamedValue((-1 * tos.countdown[0]) - 1).asInteger();
		}
		if (tos.countdown[0] == 0) {
			return this.in(tos.countdown[1]);
		}
		return IEffector.RTE_EFFECT_NONE;
	}

	private int in(final int signal) throws InputException {
		assert (signal >= Base.RTE_SIGNAL_BASE);
		ByteInput input = new ByteInput(
			new byte[][] { Base.encodeReferenceOrdinal(Base.TYPE_REFERENCE_SIGNAL, signal) }
		);
		this.inputStack.push(input);
		return IEffector.RTE_EFFECT_PUSH;
	}

	private int in(final byte[][] input) throws InputException {
		this.inputStack.push(new ByteInput(input));
		return IEffector.RTE_EFFECT_PUSH;
	}

	private int pushTransducer(final Integer transducerOrdinal) throws EffectorException {
		try {
			this.transducerStack.push(this.model.loadTransducer(transducerOrdinal));
			return IEffector.RTE_EFFECT_START;
		} catch (final ModelException e) {
			throw new EffectorException(String.format("The start effector failed to load %1$s", this.model.getTransducerName(transducerOrdinal)), e);
		}
	}

	private int popTransducer() {
		this.transducerStack.pop();
		if (this.transducerStack.isEmpty()) {
			return IEffector.RTE_EFFECT_STOPPED;
		} else {
			return IEffector.RTE_EFFECT_STOP;
		}
	}

	private String getErrorInput(final int state, int errorInput) {
		String error = "\n\tTransducer stack:\n";
		this.transducerStack.peek().state = state;
		for (int t = this.transducerStack.tos(); t >= 0; t--) {
			TransducerState transducerState = this.transducerStack.peek(t);
			int errorState = transducerState.state / transducerState.transducer.getInputEquivalentsCount();
			error += String.format("\t\t%1$s (state %2$d, input %3$d)\n", transducerState.transducer.getName(), errorState, errorInput);
		}
		error += "\tInput stack:\n";
		for (int i = this.inputStack.tos(); i >= 0 ; i--) {
			final ByteBuffer input = this.inputStack.get(i).current();
			final byte[] array = (input != null) ? input.array() : null;
			if (array == null) {
				error += "\t\t(null)\n";
			} else if (!input.hasRemaining()) {
				error += "[ ]";
			} else if (Base.isReferenceOrdinal(array)) {
				error += String.format("\t\t[ !%1$d ]\n", Base.decodeReferenceOrdinal(Base.getReferenceType(array), array));
			} else if (input.position() < array.length) {
				assert input.position() < array.length;
				int position = input.position() - 1;
				int start = Math.max(0, position - 8);
				int end = Math.min(start + 16, input.limit());
				String inchar = "";
				int inbyte = -1;
				if (Character.isBmpCodePoint((char) array[position]) && !Character.isISOControl((char) array[position])) {
					inchar = String.format("%1$c", (char) array[position]);
				} else {
					inchar = String.format("0x%1$x", Byte.toUnsignedInt(array[position]));
				}
				inbyte = Byte.toUnsignedInt(array[position]);
				error += String.format("\t\t[ char='%1$s' (%2$d~0x%2$x); pos=%3$d; limit=%4$d < ", inchar, inbyte, position, array.length);
				while (start < end) {
					int ubyte = Byte.toUnsignedInt(array[start]);
					if ((ubyte < 0x20) || (ubyte > 0x7e)) {
						error += String.format((start != position) ? "%1$02x " : "[%1$02x] ", ubyte);
					} else {
						error += String.format((start != position) ? "%1$c " : "[%1$c] ", (char)array[start]);
					}
					start += 1;
				}
				error += "> ]\n";
			} else {
				error += "\t\t[ < end-of-input > ]\n";
			} 
		}
		return error;
	}

	byte[] copyNamedValue(final int nameIndex) {
		NamedValue value = this.namedValueHandles[nameIndex];
		assert value != null;
		if (value != null && value.getValue() != null) {
			return Arrays.copyOf(value.getValue(), this.namedValueHandles[nameIndex].getLength());
		} else {
			return Base.EMPTY;
		}
	}

	private byte[] copyNamedValue() {
		return this.copyNamedValue(this.selected.getOrdinal());
	}

	private final class InlineEffector extends BaseEffector<Transduction> {
		private InlineEffector(final Transduction transduction, final Bytes name) {
			super(transduction, name);
		}

		@Override
		public int invoke() throws EffectorException {
			throw new EffectorException(String.format("Cannot invoke inline effector '%1$s'", super.getName()));
		}
	}

	private final class PasteEffector extends BaseInputOutputEffector {
		private PasteEffector(final Transduction transduction) {
			super(transduction, Bytes.encode("paste"));
		}

		@Override
		public int invoke() throws EffectorException {
			throw new EffectorException("Cannot invoke inline effector 'paste'");
		}

		@Override
		public int invoke(final int parameterIndex) throws EffectorException {
			for (byte[] bytes : super.getParameter(parameterIndex)) {
				if (Base.getReferenceType(bytes) == Base.TYPE_REFERENCE_VALUE) {
					int valueOrdinal = Base.decodeReferenceOrdinal(Base.TYPE_REFERENCE_VALUE, bytes);
					NamedValue value = (NamedValue)super.getTarget().getNamedValue(valueOrdinal);
					assert value != null;
					if (value != null) {
						super.getTarget().paste(value.getValue(), 0, value.getLength());
					}
				} else {
					super.getTarget().paste(bytes, 0, bytes.length);
				}
			}
			return IEffector.RTE_EFFECT_NONE;
		}
	}

	private final class SelectEffector extends BaseNamedValueEffector {
		private SelectEffector(final Transduction transduction) {
			super(transduction, Bytes.encode("select"));
		}

		@Override
		public int invoke() throws EffectorException {
			return super.getTarget().select(0);
		}

		@Override
		public int invoke(final int parameterIndex) throws EffectorException {
			int valueOrdinal = super.getParameter(parameterIndex);
			return (valueOrdinal != 1) ? super.getTarget().select(valueOrdinal) : IEffector.RTE_EFFECT_NONE;
		}
	}

	private final class CopyEffector extends BaseNamedValueEffector {
		private CopyEffector(final Transduction transduction) {
			super(transduction, Bytes.encode("copy"));
		}

		@Override
		public int invoke() throws EffectorException {
			assert false;
			return IEffector.RTE_EFFECT_NONE;
		}

		@Override
		public int invoke(final int parameterIndex) throws EffectorException {
			int valueOrdinal = super.getParameter(parameterIndex);
			return (valueOrdinal != 1) ? super.getTarget().copy(valueOrdinal) : IEffector.RTE_EFFECT_NONE;
		}
	}

	private final class CutEffector extends BaseNamedValueEffector {
		private CutEffector(final Transduction transduction) {
			super(transduction, Bytes.encode("cut"));
		}

		@Override
		public int invoke() throws EffectorException {
			return super.getTarget().cut(0);
		}

		@Override
		public int invoke(final int parameterIndex) throws EffectorException {
			int valueOrdinal = super.getParameter(parameterIndex);
			return (valueOrdinal != 1) ? super.getTarget().cut(valueOrdinal) : IEffector.RTE_EFFECT_NONE;
		}
	}

	private final class ClearEffector extends BaseNamedValueEffector {
		private ClearEffector(final Transduction transduction) {
			super(transduction, Bytes.encode("clear"));
		}

		@Override
		public int invoke() throws EffectorException {
			return super.getTarget().clear(-1);
		}

		@Override
		public int invoke(final int parameterIndex) throws EffectorException {
			final int nameIndex = super.getParameter(parameterIndex);
			return super.getTarget().clear(nameIndex);
		} 
	}

	private final class InEffector extends BaseInputOutputEffector {
		private InEffector(final Transduction transduction) {
			super(transduction, Bytes.encode("in"));
		}

		@Override
		public int invoke() throws EffectorException {
			throw new EffectorException("The default in[] effector is inlined");
		}

		@Override
		public int invoke(final int parameterIndex) throws EffectorException {
			try {
				return super.getTarget().in(super.getParameter(parameterIndex));
			} catch (InputException e) {
				throw new EffectorException(String.format("The in[%1$d] effector failed", parameterIndex), e);
			}
		}
	}

	private final class OutEffector extends BaseInputOutputEffector {
		private final boolean isOutEnabled;

		private OutEffector(final Transduction transduction) {
			super(transduction, Bytes.encode("out"));
			this.isOutEnabled = System.getProperty("jrte.out.enabled", "true").equals("true");
		}

		@Override
		public int invoke() throws EffectorException {
			return IEffector.RTE_EFFECT_NONE;
		}

		@Override
		public int invoke(final int parameterIndex) throws EffectorException {
			if (this.isOutEnabled) {
				for (final byte[] bytes : super.getParameter(parameterIndex)) {
					byte[] parameter = Arrays.copyOf(bytes, bytes.length);
					if (Base.isReferenceOrdinal(bytes)) {
						assert Base.getReferenceType(bytes) == Base.TYPE_REFERENCE_VALUE;
						int ordinal = Base.decodeReferenceOrdinal(Base.TYPE_REFERENCE_VALUE, bytes);
						parameter = this.getTarget().getNamedValue(ordinal).copyValue();
					} else {
						parameter = Arrays.copyOf(bytes, bytes.length);
					}
					System.out.print(Charset.defaultCharset().decode(ByteBuffer.wrap(parameter)).toString());
				}
			}
			return IEffector.RTE_EFFECT_NONE;
		}
	}

	private final class CountEffector extends BaseParameterizedEffector<Transduction, int[]> {
		private CountEffector(final Transduction transduction) {
			super(transduction, Bytes.encode("count"));
		}
		
		@Override
		public void newParameters(final int parameterCount) {
			super.parameters = new int[parameterCount][];
		}

		@Override
		public int invoke() throws EffectorException {
			throw new EffectorException(String.format("Cannot invoke inline effector '%1$s'", super.getName()));
		}

		@Override
		public int[] compileParameter(final int parameterIndex, final byte[][] parameterList) throws TargetBindingException {
			if (parameterList.length != 2) {
				throw new TargetBindingException(String.format("%1$S.%2$S: effector requires two parameters",
					super.getTarget().getName(), super.getName()));
			}
			int count = -1;
			assert !Base.isReferenceOrdinal(parameterList[0]) : "Reference ordinal presented for <count> to CountEffector[<count> <signal>]";
			byte type = Base.getReferenceType(parameterList[0]);
			if (type == Base.TYPE_REFERENCE_VALUE) {
				Bytes valueName = new Bytes(Base.getReferenceName(parameterList[0]));
				int valueOrdinal = this.getTarget().getValueOrdinal(valueName);
				if (valueOrdinal >= 0) {
					count = -1 * (1 + valueOrdinal);
				} else {
					throw new TargetBindingException(String.format("%1$s.%2$s: Named value %3$s is not found",
						super.getTarget().getName(), super.getName(), valueName.toString()));
				}
			} else if (type == Base.TYPE_REFERENCE_NONE) {
				count = Base.decodeInt(parameterList[0], parameterList[0].length);
			}
			assert !Base.isReferenceOrdinal(parameterList[1]) : "Reference ordinal presented for <signal> to CountEffector[<count> <signal>]";
			if (Base.getReferenceType(parameterList[1]) == Base.TYPE_REFERENCE_SIGNAL) {
				Bytes signalName = new Bytes(Base.getReferenceName(parameterList[1]));
				int signalOrdinal = super.getTarget().getModel().getSignalOrdinal(signalName);
				super.setParameter(parameterIndex, new int[] { count, signalOrdinal });
				return super.getParameter(parameterIndex);
			} else {
				throw new TargetBindingException(String.format("%1$s.%2$s: invalid signal '%3$%s' for count effector",
					super.getTarget().getName(), super.getName(), Bytes.decode(parameterList[1], parameterList[1].length)));
			}		
		}

		@Override
		public int invoke(final int parameterIndex) throws EffectorException {
			try {
				return super.getTarget().count(super.getParameter(parameterIndex));
			} catch (InputException e) {
				throw new EffectorException("Exception in CountEffector", e);
			}
		}
	}

	private final class StartEffector extends BaseParameterizedEffector<Transduction, Integer> {
		private StartEffector(final Transduction transduction) {
			super(transduction, Bytes.encode("start"));
		}

		@Override
		public void newParameters(final int parameterCount) {
			super.parameters = new Integer[parameterCount];
		}

		@Override
		public int invoke() throws EffectorException {
			throw new EffectorException("The start effector requires a parameter");
		}

		@Override
		public Integer compileParameter(final int parameterIndex, final byte[][] parameterList) throws TargetBindingException {
			if (parameterList.length != 1) {
				throw new TargetBindingException("The start effector accepts at most one parameter");
			}
			assert !Base.isReferenceOrdinal(parameterList[0]);
			if (Base.getReferenceType(parameterList[0]) == Base.TYPE_REFERENCE_TRANSDUCER) {
				final Bytes name = new Bytes(Base.getReferenceName(parameterList[0]));
				final int ordinal = super.getTarget().getModel().getTransducerOrdinal(name);
				if (ordinal >= 0) {
					super.setParameter(parameterIndex, ordinal);
				} else {
					throw new TargetBindingException(String.format("Null transducer reference for start effector: %s", name.toString()));
				}
				return ordinal;
			} else {
				throw new TargetBindingException(String.format("Invalid transducer reference `$s` for start effector, requires type indicator ('$c') before the transducer name", 
					new Bytes(parameterList[0]).toString(), Base.TYPE_REFERENCE_TRANSDUCER));
			}
		}

		@Override
		public int invoke(final int parameterIndex) throws EffectorException {
			return super.getTarget().pushTransducer(super.getParameter(parameterIndex));
		}
	}

	private final class PauseEffector extends BaseEffector<Transduction> {
		private PauseEffector(final Transduction transduction) {
			super(transduction, Bytes.encode("pause"));
		}

		@Override
		public int invoke() throws EffectorException {
			return IEffector.RTE_EFFECT_PAUSE;
		}
	}

	private final class StopEffector extends BaseEffector<Transduction> {
		private StopEffector(final Transduction transduction) {
			super(transduction, Bytes.encode("stop"));
		}

		@Override
		public int invoke() throws EffectorException {
			return this.getTarget().popTransducer();
		}
	}
}
