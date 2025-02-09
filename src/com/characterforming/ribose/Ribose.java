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

package com.characterforming.ribose;

import java.io.File;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.characterforming.jrte.engine.Base;
import com.characterforming.jrte.engine.Model;
import com.characterforming.jrte.engine.ModelCompiler;
import com.characterforming.jrte.engine.Runtime;
import com.characterforming.ribose.base.ModelException;

/**
 * 
 * Provides static methods for compiling and loading ribose runtime models. 
 *
 * @author Kim Briggs
 *
 */
public final class Ribose {	
/**
 * Compile a collection of DFAs from an automata directory into a ribose model file
 * and bind them to a Target class. 
 * 
 * @param targetClass the ITarget implementation class will be instantiated as model target
 * @param ginrAutomataDirectory directory containing DFAs compiled by ginr
 * @param riboseModelFile path indicating where to create the model file and file name
 * @return false if compilation fails
 */
	public static boolean compileRiboseModel(Class<?> targetClass, File ginrAutomataDirectory, File riboseModelFile) {
		final Logger rtcLogger = Base.getCompileLogger();
		if (ITarget.class.isAssignableFrom(targetClass)) {
			if (ginrAutomataDirectory.isDirectory()) {
				Model model = null;
				try {
					if (riboseModelFile.createNewFile()) {
						model = new Model(riboseModelFile, targetClass.getName());
						return model.create()
						&& ModelCompiler.compileAutomata(model, ginrAutomataDirectory)
						&& model.save();
					} else {
						String msg = String.format("Can't overwrite existing model file : '%1$s'",
							riboseModelFile.getPath());
						rtcLogger.log(Level.SEVERE, msg);
						return false;
					}
				} catch (Exception e) {
					String msg = String.format("Exception compiling model '%1$s' from '%2$s'",
						riboseModelFile.getPath(), ginrAutomataDirectory.getPath());
					rtcLogger.log(Level.SEVERE, msg, e);
					riboseModelFile.delete();
					return false;
				} finally {
					if (model != null) {
						model.close();
					}
				}
			} else {
				String msg = String.format("Not a directory :'%1$s'",	ginrAutomataDirectory);
				rtcLogger.log(Level.SEVERE, msg);
			}
		} else {
			String msg = String.format("Can't compile ribose model %1$s, %2$s does not implement ITarget", 
				riboseModelFile.getPath(), targetClass.getName());
			rtcLogger.log(Level.SEVERE, msg);
		}
		return false;
	}

	/**
	 * Load a ribose runtime model from persistent store and bind it to a model
	 * target instance. The runtime model can be used to instantiate runtime
	 * transductors.
	 * 
	 * @param riboseModelFile path to the runtime model to load
	 * @return a live ribose runtime model instance
	 * @throws ModelException on error
	 */
	public static IRuntime loadRiboseModel(File riboseModelFile) throws ModelException {
		return new Runtime(new Model(riboseModelFile));
	}	
}
