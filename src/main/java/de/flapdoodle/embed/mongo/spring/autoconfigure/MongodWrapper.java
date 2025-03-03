/**
 * Copyright (C) 2011
 *   Michael Mosmann <michael@mosmann.de>
 *   Martin Jöhren <m.joehren@googlemail.com>
 *
 * with contributions from
 * 	...
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.flapdoodle.embed.mongo.spring.autoconfigure;

import de.flapdoodle.checks.Preconditions;
import de.flapdoodle.embed.mongo.transitions.RunningMongodProcess;
import de.flapdoodle.reverse.Listener;
import de.flapdoodle.reverse.StateID;
import de.flapdoodle.reverse.TransitionWalker;
import de.flapdoodle.reverse.Transitions;

import java.util.Arrays;
import java.util.List;

public class MongodWrapper {

	private final Transitions transitions;
	private final List<Listener> stateChangeListeners;
	private TransitionWalker.ReachedState<RunningMongodProcess> runningMongo = null;

	public MongodWrapper(Transitions transitions, Listener ... stateChangeListeners) {
		this.transitions = transitions;
		this.stateChangeListeners = Arrays.asList(stateChangeListeners);
	}

	private void start() {
		runningMongo = transitions.walker().initState(StateID.of(RunningMongodProcess.class), stateChangeListeners.toArray(new Listener[0]));
	}

	private void stop() {
		Preconditions.checkNotNull(runningMongo, "stop called, but runningMongo is null");
		runningMongo.close();
	}
}
