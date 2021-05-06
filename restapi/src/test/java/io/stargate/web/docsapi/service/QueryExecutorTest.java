/*
 * Copyright The Stargate Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.stargate.web.docsapi.service;

import static io.stargate.db.schema.Column.Kind.Clustering;
import static io.stargate.db.schema.Column.Kind.PartitionKey;
import static io.stargate.db.schema.Column.Kind.Regular;
import static org.assertj.core.api.Assertions.assertThat;

import com.datastax.oss.driver.shaded.guava.common.collect.ImmutableList;
import com.datastax.oss.driver.shaded.guava.common.collect.ImmutableMap;
import io.reactivex.Flowable;
import io.stargate.db.datastore.AbstractDataStoreTest;
import io.stargate.db.datastore.ResultSet;
import io.stargate.db.query.Predicate;
import io.stargate.db.query.builder.AbstractBound;
import io.stargate.db.query.builder.BuiltQuery;
import io.stargate.db.schema.Column.Type;
import io.stargate.db.schema.ImmutableColumn;
import io.stargate.db.schema.ImmutableKeyspace;
import io.stargate.db.schema.ImmutableSchema;
import io.stargate.db.schema.ImmutableTable;
import io.stargate.db.schema.Keyspace;
import io.stargate.db.schema.Schema;
import io.stargate.db.schema.Table;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class QueryExecutorTest extends AbstractDataStoreTest {

  protected static final Table table =
      ImmutableTable.builder()
          .keyspace("test_docs")
          .name("collection1")
          .addColumns(
              ImmutableColumn.builder().name("key").type(Type.Text).kind(PartitionKey).build())
          .addColumns(ImmutableColumn.builder().name("p0").type(Type.Text).kind(Clustering).build())
          .addColumns(ImmutableColumn.builder().name("p1").type(Type.Text).kind(Clustering).build())
          .addColumns(ImmutableColumn.builder().name("p2").type(Type.Text).kind(Clustering).build())
          .addColumns(ImmutableColumn.builder().name("p3").type(Type.Text).kind(Clustering).build())
          .addColumns(
              ImmutableColumn.builder().name("test_value").type(Type.Double).kind(Regular).build())
          .build();

  private static final Keyspace keyspace =
      ImmutableKeyspace.builder().name("test_docs").addTables(table).build();

  private static final Schema schema = ImmutableSchema.builder().addKeyspaces(keyspace).build();

  private QueryExecutor executor;
  private AbstractBound<?> allDocsQuery;
  private BuiltQuery<?> allRowsForDocQuery;

  @Override
  protected Schema schema() {
    return schema;
  }

  @BeforeEach
  public void setup() {
    executor = new QueryExecutor(datastore());
    allDocsQuery = datastore().queryBuilder().select().star().from(table).build().bind();
    allRowsForDocQuery =
        datastore().queryBuilder().select().star().from(table).where("key", Predicate.EQ).build();
  }

  private Map<String, Object> row(String id, String p0, Double value) {
    return ImmutableMap.of("key", id, "p0", p0, "test_value", value);
  }

  private Map<String, Object> row(String id, String p0, String p1, Double value) {
    return ImmutableMap.of("key", id, "p0", p0, "p1", p1, "test_value", value);
  }

  private <T> List<T> get(Flowable<T> flowable) {
    return StreamSupport.stream(flowable.blockingIterable().spliterator(), false)
        .collect(Collectors.toList());
  }

  private <T> List<T> get(Flowable<T> flowable, int limit) {
    return StreamSupport.stream(flowable.limit(limit).blockingIterable().spliterator(), false)
        .collect(Collectors.toList());
  }

  @Test
  void testFullScan() {
    withQuery(table, "SELECT * FROM %s")
        .returning(ImmutableList.of(row("1", "x", 1.0d), row("1", "y", 2.0d), row("2", "x", 3.0d)));

    List<RawDocument> r1 = get(executor.queryDocs(allDocsQuery, 2, null));
    assertThat(r1).extracting(RawDocument::id).containsExactly("1", "2");
  }

  @Test
  void testFullScanLimited() {
    withFiveTestDocs(3);

    assertThat(get(executor.queryDocs(allDocsQuery, 1, null)))
        .extracting(RawDocument::id)
        .containsExactly("1");

    assertThat(get(executor.queryDocs(allDocsQuery, 2, null)))
        .extracting(RawDocument::id)
        .containsExactly("1", "2");

    assertThat(get(executor.queryDocs(allDocsQuery, 4, null)))
        .extracting(RawDocument::id)
        .containsExactly("1", "2", "3", "4");

    assertThat(get(executor.queryDocs(allDocsQuery, 100, null)))
        .extracting(RawDocument::id)
        .containsExactly("1", "2", "3", "4", "5");
  }

  private void withFiveTestDocs(int pageSize) {
    withQuery(table, "SELECT * FROM %s")
        .withPageSize(pageSize)
        .returning(
            ImmutableList.of(
                row("1", "x", 1.0d),
                row("1", "y", 2.0d),
                row("2", "x", 3.0d),
                row("3", "x", 1.0d),
                row("4", "y", 2.0d),
                row("4", "x", 3.0d),
                row("5", "x", 3.0d),
                row("5", "x", 3.0d)));
  }

  private void withFiveTestDocIds(int pageSize) {
    withQuery(table, "SELECT * FROM %s")
        .withPageSize(pageSize)
        .returning(
            ImmutableList.of(
                row("1", "x", 1.0d),
                row("2", "x", 3.0d),
                row("3", "x", 1.0d),
                row("4", "y", 2.0d),
                row("5", "x", 3.0d)));
  }

  @ParameterizedTest
  @CsvSource({"1", "3", "5", "100"})
  void testFullScanPaged(int pageSize) {
    withFiveTestDocs(pageSize);

    List<RawDocument> r1 = get(executor.queryDocs(allDocsQuery, 4, null));
    assertThat(r1).extracting(RawDocument::id).containsExactly("1", "2", "3", "4");

    assertThat(r1.get(0).hasPagingState()).isTrue();
    ByteBuffer ps1 = r1.get(0).makePagingState();
    List<RawDocument> r2 = get(executor.queryDocs(allDocsQuery, 2, ps1));
    assertThat(r2).extracting(RawDocument::id).containsExactly("2", "3");

    assertThat(r1.get(1).hasPagingState()).isTrue();
    ByteBuffer ps2 = r1.get(1).makePagingState();
    List<RawDocument> r3 = get(executor.queryDocs(allDocsQuery, 3, ps2));
    assertThat(r3).extracting(RawDocument::id).containsExactly("3", "4", "5");

    assertThat(r1.get(3).hasPagingState()).isTrue();
    ByteBuffer ps4 = r1.get(3).makePagingState();
    List<RawDocument> r4 = get(executor.queryDocs(allDocsQuery, 100, ps4));
    assertThat(r4).extracting(RawDocument::id).containsExactly("5");
  }

  @ParameterizedTest
  @CsvSource({"1", "3", "5", "100"})
  void testFullScanFinalPagingState(int pageSize) {
    withFiveTestDocs(pageSize);

    List<RawDocument> r1 = get(executor.queryDocs(allDocsQuery, 100, null));
    assertThat(r1).extracting(RawDocument::id).containsExactly("1", "2", "3", "4", "5");
    assertThat(r1.get(4).makePagingState()).isNull();
    assertThat(r1.get(4).hasPagingState()).isFalse();
  }

  @ParameterizedTest
  @CsvSource({"1", "3", "5", "100"})
  void testPopulate(int pageSize) {
    withFiveTestDocIds(pageSize);
    withQuery(table, "SELECT * FROM %s WHERE key = ?", "2")
        .withPageSize(pageSize)
        .returning(ImmutableList.of(row("2", "x", 3.0d), row("2", "y", 1.0d)));
    withQuery(table, "SELECT * FROM %s WHERE key = ?", "5")
        .withPageSize(pageSize)
        .returning(
            ImmutableList.of(
                row("5", "x", 3.0d),
                row("5", "z", 2.0d),
                row("5", "y", 1.0d),
                row("6", "x", 1.0d))); // the last row should be ignored

    List<RawDocument> r1 = get(executor.queryDocs(allDocsQuery, 100, null));
    assertThat(r1).extracting(RawDocument::id).containsExactly("1", "2", "3", "4", "5");

    RawDocument doc2a = r1.get(1);
    assertThat(doc2a.rows()).hasSize(1);
    assertThat(doc2a.id()).isEqualTo("2");

    RawDocument doc2b =
        doc2a
            .populateFrom(executor.queryDocs(allRowsForDocQuery.bind("2"), 100, null))
            .blockingGet();
    assertThat(doc2b.id()).isEqualTo("2");
    assertThat(doc2b.rows()).hasSize(2);
    assertThat(doc2b.hasPagingState()).isTrue();
    assertThat(doc2b.makePagingState()).isEqualTo(doc2a.makePagingState());

    RawDocument doc5a = r1.get(4);
    assertThat(doc5a.id()).isEqualTo("5");
    assertThat(doc5a.rows()).hasSize(1);
    assertThat(doc5a.makePagingState()).isNull();
    assertThat(doc5a.hasPagingState()).isFalse();

    RawDocument doc5b =
        doc5a
            .populateFrom(executor.queryDocs(allRowsForDocQuery.bind("5"), 100, null))
            .blockingGet();
    assertThat(doc5b.id()).isEqualTo("5");
    assertThat(doc5b.rows()).hasSize(3);
    assertThat(doc5b.makePagingState()).isNull();
    assertThat(doc5b.hasPagingState()).isFalse();
  }

  @Test
  void testResultSetPagination() {
    withFiveTestDocs(3);

    List<ResultSet> r1 =
        get(
            executor.execute(
                datastore().queryBuilder().select().star().from(table).build().bind(), null));

    assertThat(r1.get(0).currentPageRows())
        .extracting(r -> r.getString("key"))
        .containsExactly("1", "1", "2");
    assertThat(r1.get(0).getPagingState()).isNotNull();
    assertThat(r1.get(1).currentPageRows())
        .extracting(r -> r.getString("key"))
        .containsExactly("3", "4", "4");
    assertThat(r1.get(1).getPagingState()).isNotNull();
    assertThat(r1.get(2).currentPageRows())
        .extracting(r -> r.getString("key"))
        .containsExactly("5", "5");
    assertThat(r1.get(2).getPagingState()).isNull();
    assertThat(r1).hasSize(3);
  }

  @Test
  void testSubDocuments() {
    withQuery(table, "SELECT * FROM %s WHERE key = ? AND p0 > ?", "a", "x")
        .withPageSize(3)
        .returning(
            ImmutableList.of(
                row("a", "x", "2", 1.0d),
                row("a", "x", "2", 2.0d),
                row("a", "x", "2", 3.0d),
                row("a", "x", "2", 4.0d),
                row("a", "y", "2", 5.0d),
                row("a", "y", "3", 6.0d),
                row("a", "y", "3", 7.0d)));

    List<RawDocument> docs =
        get(
            executor.queryDocs(
                3,
                datastore()
                    .queryBuilder()
                    .select()
                    .star()
                    .from(table)
                    .where("key", Predicate.EQ, "a")
                    .where("p0", Predicate.GT, "x")
                    .build()
                    .bind(),
                100,
                null));

    assertThat(docs.get(0).rows())
        .extracting(r -> r.getDouble("test_value"))
        .containsExactly(1.0d, 2.0d, 3.0d, 4.0d);
    assertThat(docs.get(0).key()).containsExactly("a", "x", "2");

    assertThat(docs.get(1).rows()).extracting(r -> r.getDouble("test_value")).containsExactly(5.0d);
    assertThat(docs.get(1).key()).containsExactly("a", "y", "2");

    assertThat(docs.get(2).rows())
        .extracting(r -> r.getDouble("test_value"))
        .containsExactly(6.0d, 7.0d);
    assertThat(docs.get(2).key()).containsExactly("a", "y", "3");
  }

  @ParameterizedTest
  @CsvSource({"1", "3", "5", "100"})
  void testSubDocumentsPaged(int pageSize) {
    withQuery(table, "SELECT * FROM %s WHERE key = ? AND p0 > ?", "a", "x")
        .withPageSize(pageSize)
        .returning(
            ImmutableList.of(
                row("a", "x", "2", 1.0d),
                row("a", "x", "2", 2.0d),
                row("a", "x", "2", 3.0d),
                row("a", "x", "2", 4.0d),
                row("a", "y", "2", 5.0d),
                row("a", "y", "2", 6.0d),
                row("a", "y", "3", 8.0d),
                row("a", "y", "3", 9.0d),
                row("a", "y", "4", 10.0d)));

    AbstractBound<?> query =
        datastore()
            .queryBuilder()
            .select()
            .star()
            .from(table)
            .where("key", Predicate.EQ, "a")
            .where("p0", Predicate.GT, "x")
            .build()
            .bind();

    List<RawDocument> docs = get(executor.queryDocs(3, query, 100, null), 2);

    assertThat(docs).hasSize(2);
    assertThat(docs.get(0).key()).containsExactly("a", "x", "2");

    assertThat(docs.get(1).key()).containsExactly("a", "y", "2");

    assertThat(docs.get(1).hasPagingState()).isTrue();
    ByteBuffer ps2 = docs.get(1).makePagingState();
    assertThat(ps2).isNotNull();

    docs = get(executor.queryDocs(3, query, 100, ps2));

    assertThat(docs).hasSize(2);
    assertThat(docs.get(0).key()).containsExactly("a", "y", "3");
    assertThat(docs.get(0).rows())
        .extracting(r -> r.getDouble("test_value"))
        .containsExactly(8.0d, 9.0d);
    assertThat(docs.get(1).key()).containsExactly("a", "y", "4");
    assertThat(docs.get(1).rows())
        .extracting(r -> r.getDouble("test_value"))
        .containsExactly(10.0d);

    assertThat(docs.get(0).hasPagingState()).isTrue();
    assertThat(docs.get(0).makePagingState()).isNotNull();
    assertThat(docs.get(1).hasPagingState()).isFalse();
    assertThat(docs.get(1).makePagingState()).isNull();
  }
}
