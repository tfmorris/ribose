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
 * You should have received copies of the GNU General Public License
 * and GNU Lesser Public License along with this program.  See
 * LICENSE-gpl-3.0. If not, see
 * <http://www.gnu.org/licenses/>.
 */

package com.characterforming.jrte.engine;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.characterforming.ribose.IEffector;
import com.characterforming.ribose.IOutput;
import com.characterforming.ribose.IParameterizedEffector;
import com.characterforming.ribose.ITarget;
import com.characterforming.ribose.base.Bytes;
import com.characterforming.ribose.base.CompilationException;
import com.characterforming.ribose.base.ModelException;
import com.characterforming.ribose.base.RiboseException;
import com.characterforming.ribose.base.Signal;
import com.characterforming.ribose.base.TargetBindingException;

/**
 * @author Kim Briggs
 */
public final class Model implements AutoCloseable {
	static final int ANONYMOUS_VALUE_ORDINAL = 0;
	static final int CLEAR_ANONYMOUS_VALUE = 1;
	static final byte[] EMPTY = {};
	static final byte[] ANONYMOUS_VALUE_NAME = Model.EMPTY;
	static final byte[] ALL_VALUE_NAME = { '*' };

	public enum TargetMode {
		none, compile, run;
	}

	private final String ioMode;
	private final File modelPath;
	private final ITarget proxyTarget;
	private final Logger rtcLogger;
	private final CharsetEncoder encoder = Base.newCharsetEncoder();
	private final CharsetDecoder decoder = Base.newCharsetDecoder();
	private HashMap<Bytes, Integer> signalOrdinalMap;
	private HashMap<Bytes, Integer> namedValueOrdinalMap;
	private HashMap<Bytes, Integer> transducerOrdinalMap;
	private HashMap<Bytes, Integer> effectorOrdinalMap;
	private ArrayList<HashMap<BytesArray, Integer>> effectorParametersMaps;
	private IEffector<?>[] proxyEffectors;
	private Transductor proxyTransductor;
	private String modelVersion;

	private volatile Transducer transducerObjectIndex[];
	private Bytes transducerNameIndex[];
	private long transducerOffsetIndex[];

	private RandomAccessFile io;
	private boolean deleteOnClose;
	private byte[][] namedValuesNames;
	private byte[][] signalNames;
	private byte[][] transducerNames;

	public Model(final File modelPath, String targetClassname) throws ModelException {
		this.ioMode = "rw";
		this.deleteOnClose = true;
		this.modelPath = modelPath;
		this.rtcLogger = Base.getCompileLogger();
		this.modelVersion = Base.RTE_VERSION;
		try {
			Class<?> targetClass = Class.forName(targetClassname);
			this.proxyTarget = (ITarget) targetClass.getDeclaredConstructor().newInstance();
			this.io = new RandomAccessFile(this.modelPath, this.ioMode);
			assert this.modelPath.length() == 0;
			this.putLong(0);
			this.putString(this.modelVersion);
			this.putString(targetClassname);
		} catch (FileNotFoundException e) {
			throw new ModelException(String.format("Unable to create model file '%s'.",
				this.modelPath.toPath().toString()), e);
		} catch (ClassNotFoundException e) {
			throw new ModelException(String.format("Unable to instantiate target class '%s'.",
				targetClassname), e);
		} catch (Exception e) {
			throw new ModelException(String.format("Class '%s' does not implement the ITarget interface.",
				targetClassname), e);
		}
	}

	public Model(final File modelPath) throws ModelException {
		this.ioMode = "r";
		this.deleteOnClose = false;
		this.modelPath = modelPath;
		this.rtcLogger = Base.getCompileLogger();
		Base.getRuntimeLogger();
		String targetClassname = "?";
		try {
			this.io = new RandomAccessFile(this.modelPath, this.ioMode);
			this.io.seek(0);
			this.getLong();
			this.modelVersion = this.getString();
			targetClassname = this.getString();
			Class<?> targetClass = Class.forName(targetClassname);
			this.proxyTarget = (ITarget) targetClass.getDeclaredConstructor().newInstance();
		} catch (ClassNotFoundException e) {
			throw new ModelException(String.format("Unable to instantiate target class '%s' from model file %s.",
				targetClassname, this.modelPath.toPath().toString()), e);
		} catch (IOException e) {
			throw new ModelException(String.format("Failed to read preamble from model file %s.",
				this.modelPath.toPath().toString()), e);
		} catch (Exception e) {
			throw new ModelException(String.format("Class '%s' from model file %s does not implement the ITarget interface.",
				targetClassname, this.modelPath.toPath().toString()), e);
		}
	}

	public void initialize(TargetMode targetMode) throws ModelException {
		this.proxyTransductor = new Transductor(this, targetMode);
		this.proxyTransductor.setNamedValueOrdinalMap(this.namedValueOrdinalMap);
		IEffector<?>[] trexFx = this.proxyTransductor.getEffectors();
		IEffector<?>[] targetFx = this.proxyTarget.getEffectors();
		this.proxyEffectors = new IEffector<?>[trexFx.length + targetFx.length];
		System.arraycopy(trexFx, 0, this.proxyEffectors, 0, trexFx.length);
		System.arraycopy(targetFx, 0, this.proxyEffectors, trexFx.length, targetFx.length);
		this.effectorParametersMaps = new ArrayList<HashMap<BytesArray, Integer>>(this.proxyEffectors.length);
		this.effectorOrdinalMap = new HashMap<Bytes, Integer>((this.proxyEffectors.length * 5) >> 2);
		for (int effectorOrdinal = 0; effectorOrdinal < this.proxyEffectors.length; effectorOrdinal++) {
			this.effectorParametersMaps.add(null);
			this.effectorOrdinalMap.put(this.proxyEffectors[effectorOrdinal].getName(), effectorOrdinal);
		}
		this.proxyTransductor.setEffectors(this.proxyEffectors);
	}

	/**
	 * Determine model version (current or previous (deprecated), else obsolete)
	 *
	 * @return the model version string
	 */
	String getModelVersion() {
		return this.modelVersion;
	}

	/**
	 * @return false if compilation fails
	 * @throws ModelException on error
	 */
	public boolean create() throws ModelException {
		assert this.modelPath.exists();
		assert this.ioMode.equals("rw");
		this.signalOrdinalMap = new HashMap<Bytes, Integer>(256);
		this.transducerOrdinalMap = new HashMap<Bytes, Integer>(256);
		this.namedValueOrdinalMap = new HashMap<Bytes, Integer>(256);
		for (int ordinal = 0; ordinal < Base.RTE_SIGNAL_BASE; ordinal++) {
			Bytes name = new Bytes(new byte[] { 0, (byte) ordinal });
			this.signalOrdinalMap.put(name, ordinal);
		}
		for (Signal signal : Signal.values()) {
			assert this.getSignalLimit() == signal.signal();
			this.signalOrdinalMap.put(signal.key(), signal.signal());
		}
		assert this.signalOrdinalMap.size() == (Base.RTE_SIGNAL_BASE + Signal.values().length);
		this.namedValueOrdinalMap.put(new Bytes(Model.ANONYMOUS_VALUE_NAME), Model.ANONYMOUS_VALUE_ORDINAL);
		this.namedValueOrdinalMap.put(new Bytes(Model.ALL_VALUE_NAME), Model.CLEAR_ANONYMOUS_VALUE);
		this.transducerObjectIndex = new Transducer[256];
		this.transducerOffsetIndex = new long[256];
		this.transducerNameIndex = new Bytes[256];
		this.initialize(TargetMode.compile);
		return true;
	}

	/**
	 * @return false if compilation fails
	 * @throws ModelException on error
	 */
	public boolean save() throws ModelException {
		File mapFile = new File(this.modelPath.getPath().replaceAll(".model", ".map"));
		try {
			long filePosition = this.seek(-1);
			int targetOrdinal = this.addTransducer(new Bytes(this.proxyTarget.getName().getBytes()));
			this.setTransducerOffset(targetOrdinal, filePosition);
			long indexPosition = this.io.getFilePointer();
			assert indexPosition == this.io.length();
			this.putOrdinalMap(signalOrdinalMap);
			this.putOrdinalMap(namedValueOrdinalMap);
			this.putOrdinalMap(effectorOrdinalMap);
			this.putOrdinalMap(transducerOrdinalMap);
			int transducerCount = this.transducerOrdinalMap.size();
			for (int index = 0; index < transducerCount; index++) {
				assert this.transducerOffsetIndex[index] > 0;
				this.putBytes(this.transducerNameIndex[index]);
				this.putLong(this.transducerOffsetIndex[index]);
			}
			this.compileModelParameters();
			this.seek(0);
			this.putLong(indexPosition);
			saveMapFile(mapFile);
			setDeleteOnClose(false);
			String msg = String.format(
				"%1$s: target class %2$s%3$s%4$d transducers; %5$d effectors; %6$d named values; %7$d signal ordinals%8$s",
				this.modelPath.getPath(), this.proxyTarget.getClass().getName(), Base.lineEnd,
				this.transducerOrdinalMap.size() - 1, this.effectorOrdinalMap.size(),
				this.namedValueOrdinalMap.size(), this.getSignalCount(), Base.lineEnd);
			this.rtcLogger.log(Level.INFO, msg);
		} catch (IOException e) {
			throw new ModelException(
				String.format("IOException caught compiling model file '%1$s'", this.modelPath.getPath()), e);
		} catch (RiboseException e) {
			throw new ModelException(
				String.format("RteException caught compiling model file '%1$s'", this.modelPath.getPath()), e);
		} finally {
			if (this.transducerOrdinalMap.size() <= 1) {
				this.rtcLogger.log(Level.WARNING, String.format("No transducers compiled to %1$s",
					this.modelPath.getPath()));
			}
			if (this.deleteOnClose) {
				this.rtcLogger.log(Level.SEVERE, "Compilation failed for model " + this.modelPath.getPath());
				mapFile.delete();
			}
			this.close();
		}
		return !this.deleteOnClose;
	}

	/**
	 * Bind target instance to runtime model.
	 *
	 * @return true unless unable to bind target to model
	 * @throws ModelException on error
	 */
	public boolean load() throws ModelException {
		boolean loaded = false;
		try {
			this.seek(0);
			long indexPosition = this.getLong();
			final String modelVersion = this.getString();
			if (!modelVersion.equals(Base.RTE_VERSION) && !modelVersion.equals(Base.RTE_PREVIOUS)) {
				throw new ModelException(String.format("Current this version '%1$s' does not match version string '%2$s' from model file '%3$s'",
					Base.RTE_VERSION, modelVersion, this.modelPath.getPath()));
			}
			final String targetClassname = this.getString();
			if (!targetClassname.equals(this.proxyTarget.getClass().getName())) {
				throw new ModelException(
					String.format("Can't load model for target class '%1$s'; '%2$s' is target class for model file '%3$s'",
						this.proxyTarget.getName(), targetClassname, this.modelPath.getPath()));
			}
			this.modelVersion = modelVersion;
			this.seek(indexPosition);
			this.signalOrdinalMap = this.getOrdinalMap();
			this.signalNames = this.getValueNames(this.signalOrdinalMap);
			this.namedValueOrdinalMap = this.getOrdinalMap();
			this.namedValuesNames = this.getValueNames(this.namedValueOrdinalMap);
			this.effectorOrdinalMap = this.getOrdinalMap();
			this.transducerOrdinalMap = this.getOrdinalMap();
			this.transducerNames = this.getValueNames(this.namedValueOrdinalMap);
			this.initialize(TargetMode.run);
			assert this.effectorOrdinalMap.size() == this.proxyEffectors.length;
			if (!this.transducerOrdinalMap.containsKey(Bytes.encode(this.encoder, this.proxyTarget.getName()))) {
				throw new ModelException(String.format("Target name '%1$s' not found in name offset map for this file '%2$s'",
					this.proxyTarget.getName(), this.modelPath.getPath()));
			}
			int transducerCount = this.transducerOrdinalMap.size();
			this.transducerNameIndex = new Bytes[transducerCount];
			this.transducerObjectIndex = new Transducer[transducerCount];
			this.transducerOffsetIndex = new long[transducerCount];
			for (int transducerOrdinal = 0; transducerOrdinal < transducerCount; transducerOrdinal++) {
				this.transducerNameIndex[transducerOrdinal] = new Bytes(this.getBytes());
				this.transducerOffsetIndex[transducerOrdinal] = this.getLong();
				this.transducerObjectIndex[transducerOrdinal] = null;
				assert this.transducerOrdinalMap.get(this.transducerNameIndex[transducerOrdinal]) == transducerOrdinal;
			}
			assert this.proxyTransductor == (Transductor)this.proxyEffectors[0].getTarget();
			for (int effectorOrdinal = 0; effectorOrdinal < this.effectorOrdinalMap.size(); effectorOrdinal++) {
				byte[][][] effectorParameters = this.getBytesArrays();
				assert effectorParameters != null;
				if (this.proxyEffectors[effectorOrdinal] instanceof IParameterizedEffector<?,?>) {
					IParameterizedEffector<?,?> effector = (IParameterizedEffector<?,?>)this.proxyEffectors[effectorOrdinal];
					effector.newParameters(effectorParameters.length);
					for (int index = 0; index < effectorParameters.length; index++) {
						effector.compileParameter(index, effectorParameters[index]);
					}
				}
			}
			loaded = true;
		} finally {
			if (!loaded) {
				this.close();
			}
		}
		return loaded;
	}

	private byte[][] getValueNames(HashMap<Bytes, Integer> nameOrdinalMap) {
		byte[][] names = new byte[nameOrdinalMap.size()][];
		for (Entry<Bytes, Integer> e : nameOrdinalMap.entrySet()) {
			names[e.getValue()] = e.getKey().getBytes();
		}
		return names;
	}

	byte[] getValueName(int valueOrdinal) {
		return this.namedValuesNames[valueOrdinal];
	}

	byte[] getSignalName(int signalOrdinal) {
		return this.signalNames[signalOrdinal];
	}

	byte[] getTransducerName(int transducerOrdinal) {
		return this.transducerNames[transducerOrdinal];
	}

	/**
	 * Instantiate a new {@code Transductor} and bind it to a runtime target.
	 *
	 * @param target the runtime target to bind the transductor to
	 * @return the bound transductor instance
	 */
	public Transductor bindTransductor(ITarget target) throws ModelException {
		assert !this.ioMode.equals("rw");
		Class<? extends ITarget> targetClass = target.getClass();
		Class<? extends ITarget> modelClass = this.proxyTarget.getClass();
		if (!modelClass.isAssignableFrom(targetClass)) {
			throw new ModelException(
				String.format("Cannot bind instance of target class '%1$s', can only bind to model target class '%2$s'",
					target.getClass().getName(), this.proxyTarget.getClass().getName()));
		}
		if (target == this.proxyTarget) {
			throw new ModelException(String.format("Cannot use model target instance as runtime target: $%s",
				this.proxyTarget.getClass().getName()));
		}
		Transductor trex = new Transductor(this, TargetMode.run);
		IEffector<?>[] trexFx = trex.getEffectors();
		IEffector<?>[] targetFx = target.getEffectors();
		IEffector<?>[] boundFx = new IEffector<?>[trexFx.length + targetFx.length];
		System.arraycopy(trexFx, 0, boundFx, 0, trexFx.length);
		System.arraycopy(targetFx, 0, boundFx, trexFx.length, targetFx.length);
		checkTargetEffectors(trex, boundFx);
		trex.setEffectors(this.bindParameters(trex, boundFx));
		return trex;
	}

	@Override
	public void close() {
		try {
			if (this.io != null) {
				this.io.close();
			}
		} catch (IOException e) {
			this.rtcLogger.log(Level.SEVERE, "Unable to close model file %1$s " + this.modelPath.getPath(), e);
		} finally {
			this.io = null;
			if (this.deleteOnClose) {
				if (this.modelPath.exists() && !this.modelPath.delete()) {
					this.rtcLogger.warning("Unable to delete invalid model file %1$s " + this.modelPath.getPath());
				}
			}
		}
	}

	private void saveMapFile(File mapFile) {
		PrintWriter mapWriter = null;
		try {
			mapWriter = new PrintWriter(mapFile);
			mapWriter.println(String.format("version\t%1$s", this.modelVersion));
			mapWriter.println(String.format("target\t%1$s", this.proxyTarget.getClass().getName()));
			Bytes[] transducerIndex = new Bytes[this.transducerOrdinalMap.size()];
			for (Map.Entry<Bytes, Integer> m : this.transducerOrdinalMap.entrySet()) {
				transducerIndex[m.getValue()] = m.getKey();
			}
			for (int i = 0; i < (transducerIndex.length - 1); i++) {
				mapWriter.println("transducer\t" + transducerIndex[i] + "\t" + i);
			}
			Bytes[] signalIndex = new Bytes[this.signalOrdinalMap.size()];
			for (Map.Entry<Bytes, Integer> m : this.signalOrdinalMap.entrySet()) {
				signalIndex[m.getValue()] = m.getKey();
			}
			for (int i = Base.RTE_SIGNAL_BASE; i < signalIndex.length; i++) {
				mapWriter.println("signal\t" + signalIndex[i] + "\t" + i);
			}
			Bytes[] effectorIndex = new Bytes[this.effectorOrdinalMap.size()];
			for (Map.Entry<Bytes, Integer> m : this.effectorOrdinalMap.entrySet()) {
				effectorIndex[m.getValue()] = m.getKey();
			}
			for (int i = 0; i < effectorIndex.length; i++) {
				mapWriter.println("effector\t" + effectorIndex[i] + "\t" + i);
			}
			Bytes[] valueIndex = new Bytes[this.namedValueOrdinalMap.size()];
			for (Map.Entry<Bytes, Integer> m : this.namedValueOrdinalMap.entrySet()) {
				valueIndex[m.getValue()] = m.getKey();
			}
			for (int i = 0; i < valueIndex.length; i++) {
				mapWriter.println("value\t" + valueIndex[i] + "\t" + i);
			}
			mapWriter.flush();
		} catch (final IOException e) {
			this.rtcLogger.log(Level.SEVERE, "Model unable to create map file " + mapFile.getPath(), e);
		} finally {
			if (mapWriter != null) {
				mapWriter.close();
			}
		}
	}

	private void checkTargetEffectors(ITarget target, IEffector<?>[] boundFx) throws ModelException {
		boolean checked = boundFx.length == this.proxyEffectors.length;
		for (int i = 0; checked && i < boundFx.length; i++) {
			checked &= boundFx[i].equivalent(this.proxyEffectors[i]);
		}
		if (!checked) {
			StringBuffer msg = new StringBuffer(256);
			msg.append("Target ").append(target.getName()).append(" effectors do not match proxy effectors.")
					.append(Base.lineEnd).append("\tTarget:");
			for (IEffector<?> fx : boundFx) {
				msg.append(' ').append(fx.getName());
			}
			msg.append(Base.lineEnd).append("\tProxy:");
			for (IEffector<?> fx : this.proxyEffectors) {
				msg.append(' ').append(fx.getName());
			}
			throw new ModelException(msg.toString());
		}
	}

	private boolean compileModelParameters() throws ModelException {
		boolean fail = false;
		final Map<Bytes, Integer> effectorOrdinalMap = this.getEffectorOrdinalMap();
		for (int effectorOrdinal = 0; effectorOrdinal < this.proxyEffectors.length; effectorOrdinal++) {
			IEffector<?> effector = this.proxyEffectors[effectorOrdinal];
			HashMap<BytesArray, Integer> parameters = this.effectorParametersMaps.get(effectorOrdinal);
			if (parameters != null) {
				if (effector instanceof IParameterizedEffector<?,?>) {
					final IParameterizedEffector<?,?> parameterizedEffector = (IParameterizedEffector<?,?>) effector;
					parameterizedEffector.newParameters(parameters.size());
					byte[][][] effectorParameters = new byte[parameters.size()][][];
					for (HashMap.Entry<BytesArray, Integer> e : parameters.entrySet()) {
						try {
							int v = e.getValue();
							byte[][] p = e.getKey().getBytes();
							parameterizedEffector.compileParameter(v, p);
							effectorParameters[v] = p;
						} catch (TargetBindingException x) {
							this.rtcLogger.log(Level.SEVERE, x.getMessage());
							fail = true;
						}
					}
					this.putBytesArrays(effectorParameters);
				} else if (parameters.size() > 0) {
					this.rtcLogger.severe(String.format("%1$s.%2$s: effector does not accept parameters",
							this.proxyTarget.getName(), effector.getName()));
					fail = true;
				} else {
					this.putInt(-1);
				}
			} else {
				this.putInt(-1);
			}
		}
		for (final Map.Entry<Bytes, Integer> entry : effectorOrdinalMap.entrySet()) {
			if (this.proxyEffectors[entry.getValue()] == null) {
				this.rtcLogger.log(Level.SEVERE, String.format("%1$s.%2$s: effector ordinal not found",
					this.proxyTarget.getName(), entry.getKey().toString()));
				fail = true;
			}
		}
		return !fail;
	}

	int compileParameters(final int effectorOrdinal, final byte[][] parameterBytes) {
		HashMap<BytesArray, Integer> parametersMap = this.effectorParametersMaps.get(effectorOrdinal);
		if (parametersMap == null) {
			parametersMap = new HashMap<BytesArray, Integer>(10);
			this.effectorParametersMaps.set(effectorOrdinal, parametersMap);
		}
		final BytesArray parametersArray = new BytesArray(parameterBytes);
		Integer parametersIndex = parametersMap.get(parametersArray);
		if (parametersIndex == null) {
			parametersIndex = parametersMap.size();
			parametersMap.put(parametersArray, parametersIndex);
		}
		return parametersIndex;
	}

	private IEffector<?>[] bindParameters(IOutput output, IEffector<?>[] runtimeEffectors) throws TargetBindingException {
		assert runtimeEffectors.length == this.proxyEffectors.length;
		for (int i = 0; i < this.proxyEffectors.length; i++) {
			runtimeEffectors[i].setOutput(output);
			if (this.proxyEffectors[i] instanceof IParameterizedEffector<?, ?>) {
				IParameterizedEffector<?, ?> modelEffector = (IParameterizedEffector<?, ?>) this.proxyEffectors[i];
				IParameterizedEffector<?, ?> boundEffector = (IParameterizedEffector<?, ?>) runtimeEffectors[i];
				int parameterCount = modelEffector.getParameterCount();
				boundEffector.newParameters(parameterCount);
				for (int j = 0; j < parameterCount; j++) {
					boundEffector.setParameter(j, modelEffector.getParameter(j));
				}
			}
		}
		return runtimeEffectors;
	}

	Map<Bytes, Integer> getEffectorOrdinalMap() {
		return Collections.unmodifiableMap(this.effectorOrdinalMap);
	}

	int getEffectorOrdinal(Bytes bytes) {
		return this.effectorOrdinalMap.getOrDefault(bytes, -1);
	}

	String parameterToString(final byte[][] parameterBytes) {
		final StringBuilder strings = new StringBuilder();
		for (final byte[] bytes : parameterBytes) {
			strings.append('[');
			strings.append(Bytes.decode(this.decoder, bytes, bytes.length));
			strings.append(']');
		}
		return strings.toString();
	}

	byte[] getProductParameter(int parameterIndex) {
		return (byte[])((IParameterizedEffector<?,?>)this.proxyEffectors[
			this.getEffectorOrdinal(Bytes.encode(this.encoder, "mproduct"))
		]).getParameter(parameterIndex);
	}

	public long[] getSumParameter(int parameterIndex) {
		return (long[])((IParameterizedEffector<?,?>)this.proxyEffectors[
			this.getEffectorOrdinal(Bytes.encode(this.encoder, "msum"))
		]).getParameter(parameterIndex);
	}

	File getModelPath() {
		return this.modelPath;
	}

	Integer getInputOrdinal(final byte[] input) throws CompilationException {
		if (input.length == 1) {
			return Byte.toUnsignedInt(input[0]);
		} else {
			Integer ordinal = this.getSignalOrdinal(new Bytes(input));
			if (ordinal < 0) {
				throw new CompilationException(String.format("Invalid input token %s",
					Bytes.decode(this.decoder, input, input.length)));
			}
			return ordinal;
		}
	}

	int getSignalCount() {
		return this.signalOrdinalMap.size() - Base.RTE_SIGNAL_BASE;
	}

	int getSignalLimit() {
		return this.signalOrdinalMap.size();
	}

	Integer getSignalOrdinal(final Bytes name) {
		return this.signalOrdinalMap.get(name);
	}

	Map<Bytes,Integer> getNamedValueMap() {
		return Collections.unmodifiableMap(this.namedValueOrdinalMap);
	}

	public String showParameter(int effectorOrdinal, int parameterIndex) {
		if (this.proxyEffectors[effectorOrdinal] instanceof IParameterizedEffector) {
			IParameterizedEffector<?,?> effector = (IParameterizedEffector<?,?>)this.proxyEffectors[effectorOrdinal];
			return effector.showParameter(parameterIndex);
		}
		return "VOID";
	}

	int addNamedValue(Bytes valueName) {
		Integer ordinal = this.namedValueOrdinalMap.get(valueName);
		if (ordinal == null) {
			ordinal = this.namedValueOrdinalMap.size();
			this.namedValueOrdinalMap.put(valueName, ordinal);
		}
		return ordinal;
	}

	int addSignal(Bytes signalName) {
		Integer ordinal = this.signalOrdinalMap.get(signalName);
		if (ordinal == null) {
			ordinal = this.getSignalLimit();
			this.signalOrdinalMap.put(signalName, ordinal);
		}
		return ordinal;
	}

	int addTransducer(Bytes transducerName) {
		Integer ordinal = this.transducerOrdinalMap.get(transducerName);
		if (ordinal == null) {
			ordinal = this.transducerOrdinalMap.size();
			this.transducerOrdinalMap.put(transducerName, ordinal);
			if (ordinal >= this.transducerNameIndex.length) {
				int length = ordinal + (ordinal << 1);
				this.transducerNameIndex = Arrays.copyOf(this.transducerNameIndex, length);
				this.transducerOffsetIndex = Arrays.copyOf(this.transducerOffsetIndex, length);
				this.transducerObjectIndex = Arrays.copyOf(this.transducerObjectIndex, length);
			}
			this.transducerNameIndex[ordinal] = transducerName;
		} else {
			assert this.transducerNameIndex[ordinal].equals(transducerName);
		}
		return ordinal;
	}

	public void setTransducerOffset(int transducerOrdinal, long offset) {
		this.transducerOffsetIndex[transducerOrdinal] = offset;
	}

	int getTransducerOrdinal(Bytes transducerName) {
		Integer ordinal = this.transducerOrdinalMap.get(transducerName);
		return (null != ordinal) ? ordinal.intValue() : -1;
	}

	long getTransducerOffset(int transducerOrdinal) {
		return (transducerOrdinal < this.transducerOffsetIndex.length)
		? this.transducerOffsetIndex[transducerOrdinal]
		: null;
	}

	Transducer getTransducer(int transducerOrdinal) {
		synchronized (this.transducerObjectIndex) {
			return (transducerOrdinal < this.transducerObjectIndex.length)
			?	this.transducerObjectIndex[transducerOrdinal]
			: null;
		}
	}

	Transducer loadTransducer(final Integer transducerOrdinal) throws ModelException {
		if ((0 <= transducerOrdinal) && (transducerOrdinal < this.transducerOrdinalMap.size())) {
			try {
				if (this.transducerObjectIndex[transducerOrdinal] == null) {
					synchronized (this.transducerObjectIndex) {
						if (this.transducerObjectIndex[transducerOrdinal] == null) {
							this.io.seek(transducerOffsetIndex[transducerOrdinal]);
							final String name = this.getString();
							final String targetName = this.getString();
							final int[] inputFilter = this.getIntArray();
							final long[] transitionMatrix = this.getTransitionMatrix();
							final int[] effectorVector = this.getIntArray();
							this.transducerObjectIndex[transducerOrdinal] = new Transducer(
								name, targetName,	inputFilter, transitionMatrix, effectorVector);
						}
					}
				}
				return this.transducerObjectIndex[transducerOrdinal];
			} catch (final IOException e) {
				throw new ModelException(
					String.format("RuntimeModel.loadTransducer(ordinal:%d) caught an IOException after seek to %d",
						transducerOrdinal, transducerOffsetIndex[transducerOrdinal]), e);
			}
		} else {
			throw new ModelException(String.format("RuntimeModel.loadTransducer(ordinal:%d) ordinal out of range [0,%d)",
				transducerOrdinal, this.transducerOrdinalMap.size()));
		}
	}

	int getInt() throws ModelException {
		long position = 0;
		try {
			position = this.io.getFilePointer();
			return this.io.readInt();
		} catch (final IOException e) {
			throw new ModelException(String.format(
				"RuntimeModel.getInt caught an IOException reading int at file position %1$d", position), e);
		}
	}

	long getLong() throws ModelException {
		long position = 0;
		try {
			position = this.io.getFilePointer();
			return this.io.readLong();
		} catch (final IOException e) {
			throw new ModelException(String.format(
				"RuntimeModel.getInt caught an IOException reading long at file position %1$d", position), e);
		}
	}

	int[] getIntArray() throws ModelException {
		int[] ints = null;
		long position = 0;
		try {
			position = this.io.getFilePointer();
			ints = new int[this.io.readInt()];
			for (int i = 0; i < ints.length; i++) {
				ints[i] = this.io.readInt();
			}
		} catch (final IOException e) {
			throw new ModelException(String.format(
				"RuntimeModel.getBytes caught an IOException reading %1$d bytes at file position %2$d  after file position %3$d",
				ints.length, position, this.getSafeFilePosition()), e);
		}
		return ints;
	}

	void putTransitionMatrix(final int[][][] matrix) throws ModelException {
		final long position = this.getSafeFilePosition();
		try {
			final int rows = matrix.length;
			final int columns = rows > 0 ? matrix[0].length : 0;
			this.io.writeInt(rows);
			this.io.writeInt(columns);
			for (int row = 0; row < rows; row++) {
				int count = 0;
				for (int column = 0; column < columns; column++) {
					if (matrix[row][column][1] != 0) {
						count++;
					}
				}
				this.io.writeInt(count);
				for (int column = 0; column < columns; column++) {
					if (matrix[row][column][1] != 0) {
						this.io.writeInt(column);
						this.io.writeInt(matrix[row][column][0]);
						this.io.writeInt(matrix[row][column][1]);
					}
				}
			}
		} catch (final IOException e) {
			throw new ModelException(String.format(
				"RuntimeModel.putTransitionMatrix caught an IOException writing transition matrix starting at file position %1$d after file position %2$d",
				position, this.getSafeFilePosition()), e);
		}
	}

	long[] getTransitionMatrix() throws ModelException {
		long[] matrix;
		long position = 0;
		try {
			position = this.io.getFilePointer();
			final int rows = this.io.readInt();
			final int columns = this.io.readInt();
			matrix = new long[columns * rows];
			// matrix is an ExS array, column index ranges over E input equivalence ordinals, row index over S states
			for (int column = 0; column < columns; column++) {
				for (int row = 0; row < rows; row++) {
					// Preset to invoke nul() effector on domain error, injects nul signal for next input
					final int toState = column * rows;
					matrix[toState + row] = Transducer.transition(toState, 0);
				}
			}
			for (int row = 0; row < rows; row++) {
				final int count = this.io.readInt();
				for (int i = 0; i < count; i++) {
					final int column = this.io.readInt();
					final int fromState = column * rows;
					final int toState = this.io.readInt() * rows;
					final int effect = this.io.readInt();
					matrix[fromState + row] = Transducer.transition(toState, effect);
				}
			}
		} catch (final IOException e) {
			throw new ModelException(String.format(
				"RuntimeModel.getTransitionMatrix caught an IOException reading transition matrix starting at file position %1$d after file position %2$d",
				position, this.getSafeFilePosition()), e);
		}
		return matrix;
	}

	byte[] getBytes() throws ModelException {
		byte[] bytes = null;
		long position = 0;
		int read = -1;
		try {
			position = this.io.getFilePointer();
			int length = this.io.readInt();
			if (length >= 0) {
				bytes = new byte[length];
				read = this.io.read(bytes);
			}
		} catch (final IOException e) {
			throw new ModelException(String.format(
				"RuntimeModel.getBytes caught an IOException reading %1$d bytes at file position %2$d  after file position %3$d",
				bytes.length, position, this.getSafeFilePosition()), e);
		}
		if (read >= 0 && read != bytes.length) {
			throw new ModelException(String.format(
				"RuntimeModel.getBytes expected %1$d bytes at file position %2$d but read only %3$d", bytes.length,
				position, read));
		}
		return bytes;
	}

	byte[][] getBytesArray() throws ModelException {
		byte[][] bytesArray = null;
		long position = 0;
		try {
			position = this.io.getFilePointer();
			int length = this.io.readInt();
			if (length >= 0) {
				bytesArray = new byte[length][];
				for (int i = 0; i < bytesArray.length; i++) {
					bytesArray[i] = this.getBytes();
				}
			}
		} catch (final IOException e) {
			throw new ModelException(String.format(
				"RuntimeModel.getBytesArray caught an IOException reading bytes array starting at file position %2$d",
				position, this.getSafeFilePosition()), e);
		}
		return bytesArray;
	}

	HashMap<Bytes, Integer> getOrdinalMap() throws ModelException {
		byte[][] bytesArray = this.getBytesArray();
		if (bytesArray != null) {
			HashMap<Bytes, Integer> map = new HashMap<Bytes, Integer>((bytesArray.length * 5) >> 2);
			for (int ordinal = 0; ordinal < bytesArray.length; ordinal++) {
				map.put(new Bytes(bytesArray[ordinal]), ordinal);
			}
			return map;
		}
		return null;
	}

	byte[][][] getBytesArrays() throws ModelException {
		byte[][][] bytesArrays = null;
		long position = 0;
		try {
			position = this.io.getFilePointer();
			int length = this.io.readInt();
			if (length >= 0) {
				bytesArrays = new byte[length][][];
				for (int i = 0; i < bytesArrays.length; i++) {
					bytesArrays[i] = this.getBytesArray();
				}
			}
		} catch (final IOException e) {
			throw new ModelException(String.format(
				"RuntimeModel.getBytesArray caught an IOException reading bytes array starting at file position after file position %2$d",
				position, this.getSafeFilePosition()), e);
		}
		return (bytesArrays != null) ? bytesArrays : new byte[][][] {};
	}

	String getString() throws ModelException {
		byte bytes[] = this.getBytes();
		return Bytes.decode(this.decoder, bytes, bytes.length).toString();
	}

	String[] getStringArray() throws ModelException {
		final byte[][] bytesArray = this.getBytesArray();
		final String[] stringArray = new String[bytesArray.length];
		for (int i = 0; i < bytesArray.length; i++) {
			stringArray[i++] = Bytes.decode(this.decoder, bytesArray[i], bytesArray[i].length).toString();
		}
		return stringArray;
	}

	long getSafeFilePosition() {
		try {
			return this.io.getFilePointer();
		} catch (final IOException e) {
			return -1;
		}
	}

	void putBytes(final byte[] bytes) throws ModelException {
		final long position = this.getSafeFilePosition();
		try {
			if (bytes != null) {
				this.io.writeInt(bytes.length);
				this.io.write(bytes);
			} else {
				this.io.writeInt(-1);
			}
		} catch (final IOException e) {
			throw new ModelException(String.format(
				"RuntimeModel.putBytes caught an IOException writing %1$d bytes at file position %2$d after file position %3$d",
				bytes.length, position, this.getSafeFilePosition()), e);
		}
	}

	void putBytes(final Bytes bytes) throws ModelException {
		this.putBytes((bytes != null) ? bytes.getBytes() : null);
	}

	void putBytes(final ByteBuffer byteBuffer) throws ModelException {
		byte bytes[] = null;
		if (byteBuffer != null) {
			bytes = new byte[byteBuffer.limit() - byteBuffer.position()];
			byteBuffer.get(bytes, byteBuffer.position(), byteBuffer.limit());
		}
		this.putBytes(bytes);
	}

	void putBytesArray(final Bytes[] bytesArray) throws ModelException {
		final long position = this.getSafeFilePosition();
		try {
			if (bytesArray != null) {
				this.io.writeInt(bytesArray.length);
				for (final Bytes element : bytesArray) {
					this.putBytes(element);
				}
			} else {
				this.io.writeInt(-1);
			}
		} catch (final IOException e) {
			throw new ModelException(String.format(
				"RuntimeModel.putBytesArray caught an IOException writing bytes array starting at file position after file position %2$d",
				position, this.getSafeFilePosition()), e);
		}
	}

	void putBytesArray(final byte[][] bytesArray) throws ModelException {
		final long position = this.getSafeFilePosition();
		try {
			if (bytesArray != null) {
				this.io.writeInt(bytesArray.length);
				for (final byte[] element : bytesArray) {
					this.putBytes(element);
				}
			} else {
				this.io.writeInt(-1);
			}
		} catch (final IOException e) {
			throw new ModelException(String.format(
					"RuntimeModel.putBytesArray caught an IOException writing bytes array starting at file position after file position %2$d",
					position, this.getSafeFilePosition()), e);
		}
	}

	void putBytesArrays(final byte[][][] bytesArrays) throws ModelException {
		final long position = this.getSafeFilePosition();
		try {
			if (bytesArrays != null) {
				this.io.writeInt(bytesArrays.length);
				for (final byte[][] bytesArray : bytesArrays) {
					this.putBytesArray(bytesArray);
				}
			} else {
				this.io.writeInt(-1);
			}
		} catch (final IOException e) {
			throw new ModelException(String.format(
				"RuntimeModel.putBytesArrays caught an IOException writing bytes arrays starting at file position after file position %2$d",
				position, this.getSafeFilePosition()), e);
		}
	}

	void putOrdinalMap(final Map<Bytes, Integer> map) throws ModelException {
		byte names[][] = new byte[map.size()][];
		for (Entry<Bytes, Integer> entry : map.entrySet()) {
			names[entry.getValue()] = entry.getKey().getBytes();
		}
		this.putBytesArray(names);
	}

	void putInt(final int i) throws ModelException {
		final long position = this.getSafeFilePosition();
		try {
			this.io.writeInt(i);
		} catch (final IOException e) {
			throw new ModelException(String.format(
				"RuntimeModel.putInt caught an IOException writing %1$d at file position %2$d after file position %3$d",
				i, position, this.getSafeFilePosition()), e);
		}
	}

	void putLong(final long i) throws ModelException {
		final long position = this.getSafeFilePosition();
		try {
			this.io.writeLong(i);
		} catch (final IOException e) {
			throw new ModelException(String.format(
				"RuntimeModel.putLong caught an IOException writing %1$d at file position %2$d after file position %3$d",
				i, position, this.getSafeFilePosition()), e);
		}
	}

	public void putIntArray(final int[] ints) throws ModelException {
		final long position = this.getSafeFilePosition();
		try {
			this.io.writeInt(ints.length);
			for (final int j : ints) {
				this.putInt(j);
			}
		} catch (final IOException e) {
			throw new ModelException(String.format(
				"RuntimeModel.putIntArray caught an IOException writing int array starting at file position after file position %2$d",
				position, this.getSafeFilePosition()), e);
		}
	}

	public void putString(final String s) throws ModelException {
		this.putBytes(Bytes.encode(this.encoder, s));
	}

	public long seek(final long filePosition) throws ModelException {
		try {
			this.io.seek(filePosition != -1 ? filePosition : this.io.length());
			return this.io.getFilePointer();
		} catch (final IOException e) {
			throw new ModelException(String.format(
				"RuntimeModel.seek caught an IOException seeking to file posiiton %1$d", filePosition), e);
		}
	}

	void setDeleteOnClose(boolean deleteOnClose) {
		this.deleteOnClose = deleteOnClose;
	}
}
