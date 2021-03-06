/*
 * Copyright The OpenTelemetry Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.opentelemetry.auto.instrumentation.mongo;

import com.mongodb.ServerAddress;
import com.mongodb.connection.ClusterId;
import com.mongodb.connection.ConnectionDescription;
import com.mongodb.connection.ConnectionId;
import com.mongodb.connection.ServerId;
import com.mongodb.event.CommandStartedEvent;
import io.opentelemetry.auto.bootstrap.instrumentation.decorator.DatabaseClientTracer;
import io.opentelemetry.auto.bootstrap.instrumentation.jdbc.DbSystem;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.bson.BsonValue;

public class MongoClientTracer extends DatabaseClientTracer<CommandStartedEvent, BsonDocument> {
  public static final MongoClientTracer TRACER = new MongoClientTracer();

  // TODO use tracer names *.mongo-3.1, *.mongo-3.7, *.mongo-async-3.3 respectively in each module
  @Override
  protected String getInstrumentationName() {
    return "io.opentelemetry.auto.mongo";
  }

  @Override
  protected String dbSystem(final CommandStartedEvent event) {
    return DbSystem.MONGODB;
  }

  @Override
  protected String dbUser(final CommandStartedEvent event) {
    return null;
  }

  @Override
  protected String dbName(final CommandStartedEvent event) {
    // Use description if set.
    ConnectionDescription connectionDescription = event.getConnectionDescription();
    if (connectionDescription != null) {
      ConnectionId connectionId = connectionDescription.getConnectionId();
      if (connectionId != null) {
        ServerId serverId = connectionId.getServerId();
        if (serverId != null) {
          ClusterId clusterId = serverId.getClusterId();
          if (clusterId != null) {
            String description = clusterId.getDescription();
            if (description != null) {
              return description;
            }
          }
        }
      }
    }
    // Fallback to db name.
    return event.getDatabaseName();
  }

  @Override
  protected InetSocketAddress peerAddress(CommandStartedEvent event) {
    if (event.getConnectionDescription() != null
        && event.getConnectionDescription().getServerAddress() != null) {
      return event.getConnectionDescription().getServerAddress().getSocketAddress();
    } else {
      return null;
    }
  }

  @Override
  protected String dbConnectionString(final CommandStartedEvent event) {
    ConnectionDescription connectionDescription = event.getConnectionDescription();
    if (connectionDescription != null) {
      ServerAddress sa = connectionDescription.getServerAddress();
      if (sa != null) {
        // https://docs.mongodb.com/manual/reference/connection-string/
        String host = sa.getHost();
        int port = sa.getPort();
        if (host != null && port != 0) {
          return "mongodb://" + host + ":" + port;
        }
      }
    }
    return null;
  }

  @Override
  public String normalizeQuery(final BsonDocument statement) {
    // scrub the Mongo command so that parameters are removed from the string
    BsonDocument scrubbed = scrub(statement);
    return scrubbed.toString();
  }

  /**
   * The values of these mongo fields will not be scrubbed out. This allows the non-sensitive
   * collection names to be captured.
   */
  private static final List<String> UNSCRUBBED_FIELDS =
      Arrays.asList("ordered", "insert", "count", "find", "create");

  private static final BsonValue HIDDEN_CHAR = new BsonString("?");

  private static BsonDocument scrub(final BsonDocument origin) {
    BsonDocument scrub = new BsonDocument();
    for (Map.Entry<String, BsonValue> entry : origin.entrySet()) {
      if (UNSCRUBBED_FIELDS.contains(entry.getKey()) && entry.getValue().isString()) {
        scrub.put(entry.getKey(), entry.getValue());
      } else {
        BsonValue child = scrub(entry.getValue());
        scrub.put(entry.getKey(), child);
      }
    }
    return scrub;
  }

  private static BsonValue scrub(final BsonArray origin) {
    BsonArray scrub = new BsonArray();
    for (BsonValue value : origin) {
      BsonValue child = scrub(value);
      scrub.add(child);
    }
    return scrub;
  }

  private static BsonValue scrub(final BsonValue origin) {
    BsonValue scrubbed;
    if (origin.isDocument()) {
      scrubbed = scrub(origin.asDocument());
    } else if (origin.isArray()) {
      scrubbed = scrub(origin.asArray());
    } else {
      scrubbed = HIDDEN_CHAR;
    }
    return scrubbed;
  }
}
