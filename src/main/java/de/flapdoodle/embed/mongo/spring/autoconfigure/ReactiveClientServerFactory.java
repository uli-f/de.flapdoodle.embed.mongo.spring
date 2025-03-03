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

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoCredential;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoClients;
import com.mongodb.reactivestreams.client.MongoDatabase;
import de.flapdoodle.checks.Preconditions;
import de.flapdoodle.embed.mongo.commands.MongodArguments;
import de.flapdoodle.embed.mongo.commands.ServerAddress;
import de.flapdoodle.embed.mongo.distribution.IFeatureAwareVersion;
import de.flapdoodle.embed.mongo.packageresolver.Feature;
import de.flapdoodle.embed.mongo.transitions.Mongod;
import de.flapdoodle.embed.mongo.transitions.RunningMongodProcess;
import de.flapdoodle.reverse.Listener;
import de.flapdoodle.reverse.StateID;
import org.bson.Document;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.mongo.MongoProperties;

import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

public class ReactiveClientServerFactory extends AbstractServerFactory {
	private static Logger logger = LoggerFactory.getLogger(ReactiveClientServerFactory.class);

	ReactiveClientServerFactory(MongoProperties properties) {
		super(properties);
		logger.info("reactive server factory");
	}

	MongodWrapper createWrapper(
		IFeatureAwareVersion version,
		Mongod mongod,
		MongodArguments mongodArguments
	) {
		return new MongodWrapper(
			mongod.transitions(version),
			addAuthUserToDB(properties),
			initReplicaSet(version, mongodArguments)
		);
	}

	protected Listener initReplicaSet(IFeatureAwareVersion version, MongodArguments mongodArguments) {
		Listener.TypedListener.Builder builder = Listener.typedBuilder();

		if (mongodArguments.replication().isPresent() && version.enabled(Feature.RS_INITIATE)) {
			builder.onStateReached(StateID.of(RunningMongodProcess.class), runningMongodProcess -> {
				try {
					try (MongoClient client = client(runningMongodProcess.getServerAddress())) {
						get(client.getDatabase("admin")
							.runCommand(Document.parse("{replSetInitiate: {}}")));
					}
				}
				catch (UnknownHostException e) {
					throw new RuntimeException(e);
				}
			});
		}

		return builder.build();
	}

	@Override
	protected Consumer<RunningMongodProcess> addAuthUserToDBCallback(String username, char[] password, String databaseName) {
		return runningMongodProcess -> {
			try {
				logger.info("enable "+username+" access for "+databaseName);

				ServerAddress serverAddress = runningMongodProcess.getServerAddress();

				String adminDatabaseName = "admin";

				try (MongoClient client = client(serverAddress)) {
					if (!createUser(client.getDatabase(adminDatabaseName), username, password, "root")) {
						throw new IllegalArgumentException("could not create "+username+" user in "+adminDatabaseName);
					}
				}

				try (MongoClient client = client(serverAddress,MongoCredential.createCredential(username, adminDatabaseName, password))) {
					if (!createUser(client.getDatabase(databaseName), username, password, "readWrite")) {
						throw new IllegalArgumentException("could not create "+username+" in "+databaseName);
					}
				}

				try (MongoClient client = client(serverAddress,MongoCredential.createCredential(username, "test", password))) {
					// if this does not fail, setup is done
					Preconditions.checkNotNull(client.getDatabase(databaseName).getName(),"something went wrong");
				}
				logger.info("access for "+username+"@"+databaseName+" is enabled");
			}
			catch (UnknownHostException ux) {
				throw new RuntimeException(ux);
			}
		};
	}

	@Override
	protected Consumer<RunningMongodProcess> sendShutdown(String username, char[] password, String databaseName) {
		return runningMongodProcess -> {
			try {
				logger.info("enable "+username+" access for "+databaseName+" - shutdown database");
				ServerAddress serverAddress = runningMongodProcess.getServerAddress();

				String adminDatabaseName = "admin";

				try (MongoClient client = client(serverAddress,MongoCredential.createCredential(username, adminDatabaseName, password))) {
					get(client.getDatabase(adminDatabaseName).runCommand(new Document()
						.append("shutdown", 1)));
				}
				logger.info("access for "+username+"@"+databaseName+" is enabled - shutdown done");
			}
			catch (UnknownHostException ux) {
				throw new RuntimeException(ux);
			}
		};
	}

	private static MongoClient client(ServerAddress serverAddress) {
		return MongoClients.create("mongodb://"+serverAddress);
	}

	private static MongoClient client(ServerAddress serverAddress, MongoCredential credential) {
		return MongoClients.create(MongoClientSettings.builder()
			.applyConnectionString(new ConnectionString("mongodb://"+serverAddress))
			.credential(credential)
			.build());
	}


	private static boolean createUser(MongoDatabase db, String username, char[] password, String ... roles) {
		Publisher<Document> result = db.runCommand(new Document()
			.append("createUser", username)
			.append("pwd", new String(password))
			.append("roles", Arrays.asList(roles))
		);
		return Preconditions.checkNotNull(get(result),"create user failed")
			.get("ok", Double.class) >= 1.0;
	}

	private static <T> T get(Publisher<T> publisher) {
		CompletableFuture<T> result = new CompletableFuture<>();

		publisher.subscribe(new Subscriber<T>() {
			@Override public void onSubscribe(Subscription s) {
				s.request(1);
			}
			@Override public void onNext(T t) {
				result.complete(t);
			}
			@Override public void onError(Throwable t) {
				result.completeExceptionally(t);
			}
			@Override public void onComplete() {
			}
		});

		try {
			return result.get();
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		}
		catch (ExecutionException e) {
			throw new RuntimeException(e);
		}
	}

}
