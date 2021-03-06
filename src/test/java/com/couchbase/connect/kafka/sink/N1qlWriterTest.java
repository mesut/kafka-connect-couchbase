package com.couchbase.connect.kafka.sink;


import com.couchbase.client.java.AsyncBucket;
import com.couchbase.client.java.PersistTo;
import com.couchbase.client.java.ReplicateTo;
import com.couchbase.client.java.document.json.JsonObject;
import com.couchbase.client.java.query.AsyncN1qlQueryResult;
import com.couchbase.client.java.query.AsyncN1qlQueryRow;
import com.couchbase.client.java.query.DefaultAsyncN1qlQueryResult;
import com.couchbase.client.java.query.DefaultAsyncN1qlQueryRow;
import com.couchbase.client.java.query.N1qlMetrics;
import com.couchbase.client.java.query.N1qlQuery;
import com.couchbase.client.java.query.ParameterizedN1qlQuery;
import com.couchbase.connect.kafka.util.JsonBinaryDocument;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.runners.MockitoJUnitRunner;
import rx.Completable;
import rx.Observable;

import java.util.ArrayList;
import java.util.List;

import static com.couchbase.client.deps.io.netty.util.CharsetUtil.UTF_8;
import static junit.framework.TestCase.assertNotNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@RunWith(MockitoJUnitRunner.class)
public class N1qlWriterTest {

    private N1qlWriter writer;

    Observable<AsyncN1qlQueryResult> emptyResult = Observable.empty();

    @Mock
    private AsyncBucket bucket;

    @Captor
    private ArgumentCaptor<N1qlQuery> argument;

    @Before
    public void before() {

        Mockito.when(bucket.name()).thenReturn("default");
    }

    private Completable write(JsonObject object) {

        return write(object, N1qlMode.UPDATE, null, emptyResult);

    }


    private Completable write(JsonObject object, N1qlMode mode, List<String> fields, Observable<AsyncN1qlQueryResult> result) {
        writer = new N1qlWriter(mode, fields, true);

        Mockito.when(bucket.query(Mockito.any(ParameterizedN1qlQuery.class))).thenReturn(result);

        JsonBinaryDocument document = null;
        if (object != null) {
            document = JsonBinaryDocument.create("id", object.toString().getBytes(UTF_8));
        }

        return writer.write(bucket, document, PersistTo.NONE, ReplicateTo.NONE);
    }

    @Test
    public void doesNotGenerateStatementOnNull() {
        write(null);

        verify(bucket, never()).query(argument.capture());
    }

    @Test
    public void doesNotGenerateStatementOnEmpty() {
        write(JsonObject.empty());
        verify(bucket, never()).query(argument.capture());
    }

    @Test
    public void doesNotGenerateStatementOnNoFields() {
        write(JsonObject.create());
        verify(bucket, never()).query(argument.capture());
    }

    @Test
    public void generateStatementOnJsonPrimitives() {
        JsonObject object = JsonObject.empty()
                .put("string", "string")
                .put("int", 10)
                .put("boolean", true)
                .put("double", 10.1)
                .put("long", 10L);

        write(object);

        verify(bucket).query(argument.capture());

        ParameterizedN1qlQuery query = (ParameterizedN1qlQuery) argument.getValue();
        String statement = query.statement().toString();

        assertTrue(statement.contains("`boolean` = $boolean"));
        assertTrue(statement.contains("`string` = $string"));
        assertTrue(statement.contains("`double` = $double"));
        assertTrue(statement.contains("`int` = $int"));
        assertTrue(statement.contains("`long` = $long"));

        assertEquals(object.put("__id__", "id").toString(), query.statementParameters().toString());
    }

    @Test
    public void generateStatement() {
        JsonObject object = JsonObject.empty().put("test", "string");

        write(object);

        verify(bucket).query(argument.capture());

        ParameterizedN1qlQuery query = (ParameterizedN1qlQuery) argument.getValue();

        assertNotNull(query);
        assertEquals("UPDATE `default` USE KEYS $__id__ SET `test` = $test RETURNING meta().id;", query.statement().toString());
        assertEquals(object.put("__id__", "id"), query.statementParameters());
    }

    @Test
    public void generateStatementWithCondition() {
        JsonObject object = JsonObject.empty().put("test", "string");

        List<String> fields = new ArrayList<>();
        fields.add("styleNumber");
        write(object, N1qlMode.UPDATE_WHERE, fields, emptyResult);

        verify(bucket).query(argument.capture());

        ParameterizedN1qlQuery query = (ParameterizedN1qlQuery) argument.getValue();

        assertNotNull(query);
        assertEquals("UPDATE `default` SET `test` = $test WHERE `styleNumber` = $styleNumber RETURNING meta().id;", query.statement().toString());
        assertEquals(object.put("__id__", "id"), query.statementParameters());
    }


    @Test
    public void generateStatementWithConditionAndStaticValueAtEnd() {
        JsonObject object = JsonObject.empty().put("test", "string");

        List<String> fields = new ArrayList<>();
        fields.add("styleNumber");
        fields.add("documentType:option");
        write(object, N1qlMode.UPDATE_WHERE, fields, emptyResult);

        verify(bucket).query(argument.capture());

        ParameterizedN1qlQuery query = (ParameterizedN1qlQuery) argument.getValue();

        assertNotNull(query);
        assertEquals("UPDATE `default` SET `test` = $test WHERE `styleNumber` = $styleNumber AND `documentType` = 'option' RETURNING meta().id;", query.statement().toString());
        assertEquals(object.put("__id__", "id"), query.statementParameters());
    }

    @Test
    public void generateStatementWithConditionAndStaticValueAtStart() {
        JsonObject object = JsonObject.empty().put("test", "string");

        List<String> fields = new ArrayList<>();
        fields.add("documentType:option");
        fields.add("styleNumber");

        write(object, N1qlMode.UPDATE_WHERE, fields, emptyResult);

        verify(bucket).query(argument.capture());

        ParameterizedN1qlQuery query = (ParameterizedN1qlQuery) argument.getValue();

        assertNotNull(query);
        assertEquals("UPDATE `default` SET `test` = $test WHERE `documentType` = 'option' AND `styleNumber` = $styleNumber RETURNING meta().id;", query.statement().toString());
        assertEquals(object.put("__id__", "id"), query.statementParameters());
    }

    @Test
    public void doesNotCreateDocumentWhenUpdateReturns1Row() {

        DefaultAsyncN1qlQueryRow row = new DefaultAsyncN1qlQueryRow(new byte[0]);
        ArrayList<AsyncN1qlQueryRow> rows = new ArrayList<>();
        rows.add(row);

        AsyncN1qlQueryResult result = new DefaultAsyncN1qlQueryResult(Observable.from(rows),
                Observable.empty(),
                Observable.empty(),
                Observable.empty(),
                Observable.empty(),
                Observable.empty(),
                true,
                "",
                "");

        ArrayList<AsyncN1qlQueryResult> results = new ArrayList<>();
        results.add(result);
        Observable<AsyncN1qlQueryResult> asyncResult = Observable.from(results);

        JsonObject object = JsonObject.create().put("test", "test");

        Completable r = write(object, N1qlMode.UPDATE, null, asyncResult);

        r.await();

        verify(bucket).query(argument.capture());
    }

    @Test
    public void createDocumentWhenUpdateReturns0Row() {

        ArrayList<AsyncN1qlQueryRow> rows = new ArrayList<>();
        N1qlMetrics metrics = new N1qlMetrics(JsonObject.create().put("mutationCount", 0));

        AsyncN1qlQueryResult result = new DefaultAsyncN1qlQueryResult(Observable.from(rows),
                Observable.empty(),
                Observable.just(metrics),
                Observable.empty(),
                Observable.empty(),
                Observable.empty(),
                true,
                "",
                "");

        ArrayList<AsyncN1qlQueryResult> results = new ArrayList<>();
        results.add(result);

        Observable<AsyncN1qlQueryResult> asyncResult = Observable.from(results);

        JsonObject object = JsonObject.create().put("test", "test");

        Completable r = write(object, N1qlMode.UPDATE, null, asyncResult);

        r.await();

        verify(bucket, Mockito.times(2)).query(argument.capture());
    }

}
