/*
 * Copyright 2010-2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.mongodb.core;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;
import static org.mockito.Matchers.*;
import static org.mockito.Mockito.*;

import java.math.BigInteger;
import java.util.Collections;
import java.util.regex.Pattern;

import org.bson.types.ObjectId;
import org.hamcrest.core.Is;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.mockito.Matchers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.runners.MockitoJUnitRunner;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.convert.converter.Converter;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.domain.Sort;
import org.springframework.data.geo.Point;
import org.springframework.data.mongodb.MongoDbFactory;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.convert.CustomConversions;
import org.springframework.data.mongodb.core.convert.DefaultDbRefResolver;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.convert.QueryMapper;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;
import org.springframework.data.mongodb.core.index.MongoPersistentEntityIndexCreator;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.data.mongodb.core.mapreduce.MapReduceOptions;
import org.springframework.data.mongodb.core.query.BasicQuery;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.NearQuery;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.mongodb.test.util.IsBsonObject;
import org.springframework.test.util.ReflectionTestUtils;

import com.mongodb.BasicDBObject;
import com.mongodb.BasicDBObjectBuilder;
import com.mongodb.CommandResult;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.DBCursor;
import com.mongodb.DBObject;
import com.mongodb.MapReduceCommand;
import com.mongodb.MapReduceOutput;
import com.mongodb.Mongo;
import com.mongodb.MongoException;
import com.mongodb.ReadPreference;

/**
 * Unit tests for {@link MongoTemplate}.
 * 
 * @author Oliver Gierke
 * @author Christoph Strobl
 * @author Mark Paluch
 */
@RunWith(MockitoJUnitRunner.class)
public class MongoTemplateUnitTests extends MongoOperationsUnitTests {

	MongoTemplate template;

	@Mock MongoDbFactory factory;
	@Mock Mongo mongo;
	@Mock DB db;
	@Mock DBCollection collection;
	@Mock DBCursor cursor;

	MongoExceptionTranslator exceptionTranslator = new MongoExceptionTranslator();
	MappingMongoConverter converter;
	MongoMappingContext mappingContext;

	@Before
	public void setUp() {

		when(cursor.copy()).thenReturn(cursor);
		when(factory.getDb()).thenReturn(db);
		when(factory.getExceptionTranslator()).thenReturn(exceptionTranslator);
		when(db.getCollection(Mockito.any(String.class))).thenReturn(collection);
		when(collection.find(Mockito.any(DBObject.class))).thenReturn(cursor);
		when(cursor.limit(anyInt())).thenReturn(cursor);
		when(cursor.sort(Mockito.any(DBObject.class))).thenReturn(cursor);
		when(cursor.hint(anyString())).thenReturn(cursor);

		this.mappingContext = new MongoMappingContext();
		mappingContext.afterPropertiesSet();

		this.converter = new MappingMongoConverter(new DefaultDbRefResolver(factory), mappingContext);
		converter.afterPropertiesSet();

		this.template = new MongoTemplate(factory, converter);
	}

	@Test(expected = IllegalArgumentException.class)
	public void rejectsNullDatabaseName() throws Exception {
		new MongoTemplate(mongo, null);
	}

	@Test(expected = IllegalArgumentException.class)
	public void rejectsNullMongo() throws Exception {
		new MongoTemplate(null, "database");
	}

	@Test(expected = DataAccessException.class)
	public void removeHandlesMongoExceptionProperly() throws Exception {
		MongoTemplate template = mockOutGetDb();
		when(db.getCollection("collection")).thenThrow(new MongoException("Exception!"));

		template.remove(null, "collection");
	}

	@Test
	public void defaultsConverterToMappingMongoConverter() throws Exception {
		MongoTemplate template = new MongoTemplate(mongo, "database");
		assertTrue(ReflectionTestUtils.getField(template, "mongoConverter") instanceof MappingMongoConverter);
	}

	@Test(expected = InvalidDataAccessApiUsageException.class)
	public void rejectsNotFoundMapReduceResource() {

		GenericApplicationContext ctx = new GenericApplicationContext();
		ctx.refresh();
		template.setApplicationContext(ctx);
		template.mapReduce("foo", "classpath:doesNotExist.js", "function() {}", Person.class);
	}

	/**
	 * @see DATAMONGO-322
	 */
	@Test(expected = InvalidDataAccessApiUsageException.class)
	public void rejectsEntityWithNullIdIfNotSupportedIdType() {

		Object entity = new NotAutogenerateableId();
		template.save(entity);
	}

	/**
	 * @see DATAMONGO-322
	 */
	@Test
	public void storesEntityWithSetIdAlthoughNotAutogenerateable() {

		NotAutogenerateableId entity = new NotAutogenerateableId();
		entity.id = 1;

		template.save(entity);
	}

	/**
	 * @see DATAMONGO-322
	 */
	@Test
	public void autogeneratesIdForEntityWithAutogeneratableId() {

		this.converter.afterPropertiesSet();

		MongoTemplate template = spy(this.template);
		doReturn(new ObjectId()).when(template).saveDBObject(Mockito.any(String.class), Mockito.any(DBObject.class),
				Mockito.any(Class.class));

		AutogenerateableId entity = new AutogenerateableId();
		template.save(entity);

		assertThat(entity.id, is(notNullValue()));
	}

	/**
	 * @see DATAMONGO-374
	 */
	@Test
	public void convertsUpdateConstraintsUsingConverters() {

		CustomConversions conversions = new CustomConversions(Collections.singletonList(MyConverter.INSTANCE));
		this.converter.setCustomConversions(conversions);
		this.converter.afterPropertiesSet();

		Query query = new Query();
		Update update = new Update().set("foo", new AutogenerateableId());

		template.updateFirst(query, update, Wrapper.class);

		QueryMapper queryMapper = new QueryMapper(converter);
		DBObject reference = queryMapper.getMappedObject(update.getUpdateObject(), null);

		verify(collection, times(1)).update(Mockito.any(DBObject.class), eq(reference), anyBoolean(), anyBoolean());
	}

	/**
	 * @see DATAMONGO-474
	 */
	@Test
	public void setsUnpopulatedIdField() {

		NotAutogenerateableId entity = new NotAutogenerateableId();

		template.populateIdIfNecessary(entity, 5);
		assertThat(entity.id, is(5));
	}

	/**
	 * @see DATAMONGO-474
	 */
	@Test
	public void doesNotSetAlreadyPopulatedId() {

		NotAutogenerateableId entity = new NotAutogenerateableId();
		entity.id = 5;

		template.populateIdIfNecessary(entity, 7);
		assertThat(entity.id, is(5));
	}

	/**
	 * @see DATAMONGO-868
	 */
	@Test
	public void findAndModifyShouldBumpVersionByOneWhenVersionFieldNotIncludedInUpdate() {

		VersionedEntity v = new VersionedEntity();
		v.id = 1;
		v.version = 0;

		ArgumentCaptor<DBObject> captor = ArgumentCaptor.forClass(DBObject.class);

		template.findAndModify(new Query(), new Update().set("id", "10"), VersionedEntity.class);
		verify(collection, times(1)).findAndModify(Matchers.any(DBObject.class),
				org.mockito.Matchers.isNull(DBObject.class), org.mockito.Matchers.isNull(DBObject.class), eq(false),
				captor.capture(), eq(false), eq(false));

		Assert.assertThat(captor.getValue().get("$inc"), Is.<Object> is(new BasicDBObject("version", 1L)));
	}

	/**
	 * @see DATAMONGO-868
	 */
	@Test
	public void findAndModifyShouldNotBumpVersionByOneWhenVersionFieldAlreadyIncludedInUpdate() {

		VersionedEntity v = new VersionedEntity();
		v.id = 1;
		v.version = 0;

		ArgumentCaptor<DBObject> captor = ArgumentCaptor.forClass(DBObject.class);

		template.findAndModify(new Query(), new Update().set("version", 100), VersionedEntity.class);
		verify(collection, times(1)).findAndModify(Matchers.any(DBObject.class), isNull(DBObject.class),
				isNull(DBObject.class), eq(false), captor.capture(), eq(false), eq(false));

		Assert.assertThat(captor.getValue().get("$set"), Is.<Object> is(new BasicDBObject("version", 100)));
		Assert.assertThat(captor.getValue().get("$inc"), nullValue());
	}

	/**
	 * @see DATAMONGO-533
	 */
	@Test
	public void registersDefaultEntityIndexCreatorIfApplicationContextHasOneForDifferentMappingContext() {

		GenericApplicationContext applicationContext = new GenericApplicationContext();
		applicationContext.getBeanFactory().registerSingleton("foo",
				new MongoPersistentEntityIndexCreator(new MongoMappingContext(), factory));
		applicationContext.refresh();

		GenericApplicationContext spy = spy(applicationContext);

		MongoTemplate mongoTemplate = new MongoTemplate(factory, converter);
		mongoTemplate.setApplicationContext(spy);

		verify(spy, times(1)).addApplicationListener(argThat(new ArgumentMatcher<MongoPersistentEntityIndexCreator>() {

			@Override
			public boolean matches(Object argument) {

				if (!(argument instanceof MongoPersistentEntityIndexCreator)) {
					return false;
				}

				return ((MongoPersistentEntityIndexCreator) argument).isIndexCreatorFor(mappingContext);
			}
		}));
	}

	/**
	 * @see DATAMONGO-566
	 */
	@Test
	public void findAllAndRemoveShouldRetrieveMatchingDocumentsPriorToRemoval() {

		BasicQuery query = new BasicQuery("{'foo':'bar'}");
		template.findAllAndRemove(query, VersionedEntity.class);
		verify(collection, times(1)).find(Matchers.eq(query.getQueryObject()));
	}

	/**
	 * @see DATAMONGO-566
	 */
	@Test
	public void findAllAndRemoveShouldRemoveDocumentsReturedByFindQuery() {

		Mockito.when(cursor.hasNext()).thenReturn(true).thenReturn(true).thenReturn(false);
		Mockito.when(cursor.next()).thenReturn(new BasicDBObject("_id", Integer.valueOf(0)))
				.thenReturn(new BasicDBObject("_id", Integer.valueOf(1)));

		ArgumentCaptor<DBObject> queryCaptor = ArgumentCaptor.forClass(DBObject.class);
		BasicQuery query = new BasicQuery("{'foo':'bar'}");
		template.findAllAndRemove(query, VersionedEntity.class);

		verify(collection, times(1)).remove(queryCaptor.capture());

		DBObject idField = DBObjectTestUtils.getAsDBObject(queryCaptor.getValue(), "_id");
		assertThat((Object[]) idField.get("$in"), is(new Object[] { Integer.valueOf(0), Integer.valueOf(1) }));
	}

	/**
	 * @see DATAMONGO-566
	 */
	@Test
	public void findAllAndRemoveShouldNotTriggerRemoveIfFindResultIsEmpty() {

		template.findAllAndRemove(new BasicQuery("{'foo':'bar'}"), VersionedEntity.class);
		verify(collection, never()).remove(Mockito.any(DBObject.class));
	}

	/**
	 * @see DATAMONGO-948
	 */
	@Test
	public void sortShouldBeTakenAsIsWhenExecutingQueryWithoutSpecificTypeInformation() {

		Query query = Query.query(Criteria.where("foo").is("bar")).with(new Sort("foo"));
		template.executeQuery(query, "collection1", new DocumentCallbackHandler() {

			@Override
			public void processDocument(DBObject dbObject) throws MongoException, DataAccessException {
				// nothing to do - just a test
			}
		});

		ArgumentCaptor<DBObject> captor = ArgumentCaptor.forClass(DBObject.class);
		verify(cursor, times(1)).sort(captor.capture());
		assertThat(captor.getValue(), equalTo(new BasicDBObjectBuilder().add("foo", 1).get()));
	}

	/**
	 * @see DATAMONGO-1166
	 */
	@Test
	public void aggregateShouldHonorReadPreferenceWhenSet() {

		when(db.command(Mockito.any(DBObject.class), Mockito.any(ReadPreference.class))).thenReturn(
				mock(CommandResult.class));
		when(db.command(Mockito.any(DBObject.class))).thenReturn(mock(CommandResult.class));
		template.setReadPreference(ReadPreference.secondary());

		template.aggregate(Aggregation.newAggregation(Aggregation.unwind("foo")), "collection-1", Wrapper.class);

		verify(this.db, times(1)).command(Mockito.any(DBObject.class), eq(ReadPreference.secondary()));
	}

	/**
	 * @see DATAMONGO-1166
	 */
	@Test
	public void aggregateShouldIgnoreReadPreferenceWhenNotSet() {

		when(db.command(Mockito.any(DBObject.class), Mockito.any(ReadPreference.class))).thenReturn(
				mock(CommandResult.class));
		when(db.command(Mockito.any(DBObject.class))).thenReturn(mock(CommandResult.class));

		template.aggregate(Aggregation.newAggregation(Aggregation.unwind("foo")), "collection-1", Wrapper.class);

		verify(this.db, times(1)).command(Mockito.any(DBObject.class));
	}

	/**
	 * @see DATAMONGO-1166
	 */
	@Test
	public void geoNearShouldHonorReadPreferenceWhenSet() {

		when(db.command(Mockito.any(DBObject.class), Mockito.any(ReadPreference.class))).thenReturn(
				mock(CommandResult.class));
		when(db.command(Mockito.any(DBObject.class))).thenReturn(mock(CommandResult.class));
		template.setReadPreference(ReadPreference.secondary());

		NearQuery query = NearQuery.near(new Point(1, 1));
		template.geoNear(query, Wrapper.class);

		verify(this.db, times(1)).command(Mockito.any(DBObject.class), eq(ReadPreference.secondary()));
	}

	/**
	 * @see DATAMONGO-1166
	 */
	@Test
	public void geoNearShouldIgnoreReadPreferenceWhenNotSet() {

		when(db.command(Mockito.any(DBObject.class), Mockito.any(ReadPreference.class))).thenReturn(
				mock(CommandResult.class));
		when(db.command(Mockito.any(DBObject.class))).thenReturn(mock(CommandResult.class));

		NearQuery query = NearQuery.near(new Point(1, 1));
		template.geoNear(query, Wrapper.class);

		verify(this.db, times(1)).command(Mockito.any(DBObject.class));
	}

	/**
	 * @see DATAMONGO-1334
	 */
	@Test
	public void mapReduceShouldUseZeroAsDefaultLimit() {

		ArgumentCaptor<MapReduceCommand> captor = ArgumentCaptor.forClass(MapReduceCommand.class);

		MapReduceOutput output = mock(MapReduceOutput.class);
		when(output.results()).thenReturn(Collections.<DBObject> emptySet());
		when(collection.mapReduce(Mockito.any(MapReduceCommand.class))).thenReturn(output);

		Query query = new BasicQuery("{'foo':'bar'}");

		template.mapReduce(query, "collection", "function(){}", "function(key,values){}", Wrapper.class);

		verify(collection).mapReduce(captor.capture());

		assertThat(captor.getValue().getLimit(), is(0));
	}

	/**
	 * @see DATAMONGO-1334
	 */
	@Test
	public void mapReduceShouldPickUpLimitFromQuery() {

		ArgumentCaptor<MapReduceCommand> captor = ArgumentCaptor.forClass(MapReduceCommand.class);

		MapReduceOutput output = mock(MapReduceOutput.class);
		when(output.results()).thenReturn(Collections.<DBObject> emptySet());
		when(collection.mapReduce(Mockito.any(MapReduceCommand.class))).thenReturn(output);

		Query query = new BasicQuery("{'foo':'bar'}");
		query.limit(100);

		template.mapReduce(query, "collection", "function(){}", "function(key,values){}", Wrapper.class);

		verify(collection).mapReduce(captor.capture());

		assertThat(captor.getValue().getLimit(), is(100));
	}

	/**
	 * @see DATAMONGO-1334
	 */
	@Test
	public void mapReduceShouldPickUpLimitFromOptions() {

		ArgumentCaptor<MapReduceCommand> captor = ArgumentCaptor.forClass(MapReduceCommand.class);

		MapReduceOutput output = mock(MapReduceOutput.class);
		when(output.results()).thenReturn(Collections.<DBObject> emptySet());
		when(collection.mapReduce(Mockito.any(MapReduceCommand.class))).thenReturn(output);

		Query query = new BasicQuery("{'foo':'bar'}");

		template.mapReduce(query, "collection", "function(){}", "function(key,values){}",
				new MapReduceOptions().limit(1000), Wrapper.class);

		verify(collection).mapReduce(captor.capture());
		assertThat(captor.getValue().getLimit(), is(1000));
	}

	/**
	 * @see DATAMONGO-1334
	 */
	@Test
	public void mapReduceShouldPickUpLimitFromOptionsWhenQueryIsNotPresent() {

		ArgumentCaptor<MapReduceCommand> captor = ArgumentCaptor.forClass(MapReduceCommand.class);

		MapReduceOutput output = mock(MapReduceOutput.class);
		when(output.results()).thenReturn(Collections.<DBObject> emptySet());
		when(collection.mapReduce(Mockito.any(MapReduceCommand.class))).thenReturn(output);

		template.mapReduce("collection", "function(){}", "function(key,values){}", new MapReduceOptions().limit(1000),
				Wrapper.class);

		verify(collection).mapReduce(captor.capture());
		assertThat(captor.getValue().getLimit(), is(1000));
	}

	/**
	 * @see DATAMONGO-1334
	 */
	@Test
	public void mapReduceShouldPickUpLimitFromOptionsEvenWhenQueryDefinesItDifferently() {

		ArgumentCaptor<MapReduceCommand> captor = ArgumentCaptor.forClass(MapReduceCommand.class);

		MapReduceOutput output = mock(MapReduceOutput.class);
		when(output.results()).thenReturn(Collections.<DBObject> emptySet());
		when(collection.mapReduce(Mockito.any(MapReduceCommand.class))).thenReturn(output);

		Query query = new BasicQuery("{'foo':'bar'}");
		query.limit(100);

		template.mapReduce(query, "collection", "function(){}", "function(key,values){}",
				new MapReduceOptions().limit(1000), Wrapper.class);

		verify(collection).mapReduce(captor.capture());

		assertThat(captor.getValue().getLimit(), is(1000));
	}

	/**
	 * @see DATAMONGO-1348
	 */
	@Test
	public void geoNearShouldMapQueryCorrectly() {

		when(db.command(Mockito.any(DBObject.class))).thenReturn(mock(CommandResult.class));

		NearQuery query = NearQuery.near(new Point(1, 1));
		query.query(Query.query(Criteria.where("customName").is("rand al'thor")));

		template.geoNear(query, WithNamedFields.class);

		ArgumentCaptor<DBObject> capture = ArgumentCaptor.forClass(DBObject.class);
		verify(this.db, times(1)).command(capture.capture());

		assertThat(capture.getValue(), IsBsonObject.isBsonObject().containing("query.custom-named-field", "rand al'thor")
				.notContaining("query.customName"));
	}

	/**
	 * @see DATAMONGO-1348
	 */
	@Test
	public void geoNearShouldMapGeoJsonPointCorrectly() {

		when(db.command(Mockito.any(DBObject.class))).thenReturn(mock(CommandResult.class));

		NearQuery query = NearQuery.near(new GeoJsonPoint(1, 2));
		query.query(Query.query(Criteria.where("customName").is("rand al'thor")));

		template.geoNear(query, WithNamedFields.class);

		ArgumentCaptor<DBObject> capture = ArgumentCaptor.forClass(DBObject.class);
		verify(this.db, times(1)).command(capture.capture());

		assertThat(capture.getValue(), IsBsonObject.isBsonObject().containing("near.type", "Point")
				.containing("near.coordinates.[0]", 1D).containing("near.coordinates.[1]", 2D));
	}

	static class WithNamedFields {

		@Id String id;

		String name;
		@Field("custom-named-field") String customName;
	}

	class AutogenerateableId {

		@Id BigInteger id;
	}

	class NotAutogenerateableId {

		@Id Integer id;

		public Pattern getId() {
			return Pattern.compile(".");
		}
	}

	static class VersionedEntity {

		@Id Integer id;
		@Version Integer version;
	}

	enum MyConverter implements Converter<AutogenerateableId, String> {

		INSTANCE;

		public String convert(AutogenerateableId source) {
			return source.toString();
		}
	}

	class Wrapper {

		AutogenerateableId foo;
	}

	/**
	 * Mocks out the {@link MongoTemplate#getDb()} method to return the {@link DB} mock instead of executing the actual
	 * behaviour.
	 * 
	 * @return
	 */
	private MongoTemplate mockOutGetDb() {

		MongoTemplate template = spy(this.template);
		stub(template.getDb()).toReturn(db);
		return template;
	}

	/* (non-Javadoc)
	  * @see org.springframework.data.mongodb.core.core.MongoOperationsUnitTests#getOperations()
	  */
	@Override
	protected MongoOperations getOperationsForExceptionHandling() {
		MongoTemplate template = spy(this.template);
		stub(template.getDb()).toThrow(new MongoException("Error!"));
		return template;
	}

	/* (non-Javadoc)
	  * @see org.springframework.data.mongodb.core.core.MongoOperationsUnitTests#getOperations()
	  */
	@Override
	protected MongoOperations getOperations() {
		return this.template;
	}
}
