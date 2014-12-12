package openrest.domain;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.persistence.ManyToOne;
import javax.persistence.OneToMany;
import javax.persistence.OneToOne;
import javax.persistence.criteria.CriteriaBuilder;

import open.rest.data.query.parser.OpenRestPartTree;
import openrest.httpquery.parser.FilterWrapper;
import openrest.httpquery.parser.Parsers;
import openrest.httpquery.parser.Parsers.SubjectWrapper;
import openrest.httpquery.parser.RequestParsingException;
import openrest.httpquery.parser.TempPart;
import openrest.query.StaticFilterFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mapping.PersistentEntity;
import org.springframework.data.mapping.PropertyPath;
import org.springframework.data.rest.webmvc.ResourceNotFoundException;
import org.springframework.util.Assert;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import data.query.JpaParameter;
import data.query.JpaParameters;
import data.query.parser.AndBranch;
import data.query.parser.OrBranch;
import data.query.parser.Part;
import data.query.parser.PartTree;
import data.query.parser.TreeBranch;
import data.query.parser.TreePart;
import data.query.parser.Part.Type;

public class PartTreeSpecificationBuilder {

	private static Logger logger = LoggerFactory.getLogger(PartTreeSpecificationBuilder.class);

	private static final Map<String, Type> PART_TYPES_MAP;
	static {
		Map<String, Type> typesMap = new HashMap<String, Type>();
		typesMap.put("between", Type.BETWEEN);
		typesMap.put("isNotNull", Type.IS_NOT_NULL);
		typesMap.put("isNull", Type.IS_NULL);
		typesMap.put("lt", Type.LESS_THAN);
		typesMap.put("le", Type.LESS_THAN_EQUAL);
		typesMap.put("gt", Type.GREATER_THAN);
		typesMap.put("ge", Type.GREATER_THAN_EQUAL);
		typesMap.put("before", Type.BEFORE);
		typesMap.put("after", Type.AFTER);
		typesMap.put("notLike", Type.NOT_LIKE);
		typesMap.put("like", Type.LIKE);
		typesMap.put("startingWith", Type.STARTING_WITH);
		typesMap.put("endingWith", Type.ENDING_WITH);
		typesMap.put("containing", Type.CONTAINING);
		typesMap.put("notIn", Type.NOT_IN);
		typesMap.put("in", Type.IN);
		typesMap.put("true", Type.TRUE);
		typesMap.put("false", Type.FALSE);
		typesMap.put("eq", Type.SIMPLE_PROPERTY);
		typesMap.put("notEq", Type.NEGATING_SIMPLE_PROPERTY);
		PART_TYPES_MAP = Collections.unmodifiableMap(typesMap);
	}

	private final PersistentEntity<?, ?> domainPersistentEntity;
	private final ObjectMapper objectMapper;
	private final CriteriaBuilder criteriaBuilder;
	private final StaticFilterFactory staticFilterFactory;

	private TempPart root = new TempPart(TempPart.Type.AND);
	private Boolean countProjection;
	private Boolean distinct;
	private List<PropertyPath> expandPropertyPaths;
	private List<PropertyPath> selectionPaths;

	private List<JpaParameter> jpaParameters = new ArrayList<JpaParameter>();
	private List<Object> parametersValues = new ArrayList<Object>();
	private TreeBranch partTreeRoot;

	private TempPart propertyTempPart;
	private boolean propertyQuery = false;
	private String propertyName;
	private Class<?> propertyType;

	private Pageable pageable;
	private Sort sort;

	public void append(Pageable pageable) {
		this.pageable = pageable;
		if(pageable != null)
			sort = pageable.getSort();
	}


	public void append(TempPart tempPart) {
		if (tempPart != null) {
			root.addPart(tempPart);
		}
	}

	public void append(String id) {
		root.addPart(new TempPart("eq", getIdPropertyName(domainPersistentEntity), new String[] { id }));
	}

	public void appendStaticFilters(String... filtersToIgnore) {
		Class<?> type = propertyTempPart != null ? propertyType : getDomainClass();
		List<FilterWrapper> filterWrappers = staticFilterFactory.get(type);
		for (FilterWrapper fw : filterWrappers) {
			boolean add = true;
			if (filtersToIgnore != null)
				for (String ignore : filtersToIgnore)
					if (fw.getName().equals(ignore))
						add = false;
			if (add)
				append(fw.getTempPart());
		}
	}

	public void append(PersistentEntity<?, ?> parentPersistentEntity, String propertyName, String parentId) throws ResourceNotFoundException {
		Assert.notNull(parentPersistentEntity);
		Assert.notNull(propertyName);
		propertyTempPart = new TempPart("eq", getIdPropertyName(parentPersistentEntity), new String[] { parentId });
		this.propertyName = propertyName;
		propertyType = parentPersistentEntity.getPersistentProperty(propertyName).getType();
		propertyQuery = true;
	}

	public OpenRestQueryParameterHolder build() {
		partTreeRoot = new AndBranch();
		partTreeRoot.addPart(getTreePart(root));
		if (propertyQuery) {
			propertyQuery = false;
			partTreeRoot.addPart(getTreePart(propertyTempPart));
			propertyQuery = true;
		}
		int pageableIndex = -1;
		int sortIndex = -1;
		
		if(pageable != null){
			parametersValues.add(pageable);
			jpaParameters.add(new JpaParameter(Pageable.class, jpaParameters.size()));
			pageableIndex = jpaParameters.size()-1;
		}
		if(sort != null){
			parametersValues.add(sort);
			jpaParameters.add(new JpaParameter(Pageable.class, jpaParameters.size()));
			sortIndex = jpaParameters.size()-1;
		}
		
		JpaParameters jpaParameters = new JpaParameters(getJpaParameters(), sortIndex, pageableIndex);
		Object values[] = parametersValues.toArray();
		OpenRestPartTree partTree = new OpenRestPartTree(partTreeRoot, null, distinct, countProjection,expandPropertyPaths, propertyName);
		return new OpenRestQueryParameterHolder(partTree, values, jpaParameters);
	}

	public void setCountProjection() {
		countProjection = true;
	}

	public void setDistinct() {
		distinct = true;
	}

	public void setExpandPropertyPaths(List<PropertyPath> expandPropertyPaths) {
		this.expandPropertyPaths = expandPropertyPaths;
	}
	
	private String getIdPropertyName(PersistentEntity<?, ?> persistentEntity) {
		if (domainPersistentEntity.getIdProperty() == null)
			throw new RequestParsingException("There is no id property in " + persistentEntity.getType());
		return persistentEntity.getIdProperty().getName();
	}

	public PartTreeSpecificationBuilder(PersistentEntity<?, ?> persistentEntity, ObjectMapper objectMapper, CriteriaBuilder criteriaBuilder,
			StaticFilterFactory staticFilterFactory) {
		Assert.notNull(persistentEntity);
		Assert.notNull(objectMapper);
		Assert.notNull(criteriaBuilder);
		Assert.notNull(staticFilterFactory);
		this.domainPersistentEntity = persistentEntity;
		this.objectMapper = objectMapper;
		this.criteriaBuilder = criteriaBuilder;
		this.staticFilterFactory = staticFilterFactory;
	}

	private TreePart getTreePart(TempPart tempPart) {
		TreePart part;
		switch (tempPart.getType()) {
		case AND:
			part = new AndBranch(tempPart.getParts().size());
			for (TempPart tPart : tempPart.getParts()) {
				((TreeBranch) part).addPart(getTreePart(tPart));
			}
			return part;
		case OR:
			part = new OrBranch(tempPart.getParts().size());
			for (TempPart tPart : tempPart.getParts()) {
				((TreeBranch) part).addPart(getTreePart(tPart));
			}
			return part;
		case LEAF:
			return getLeaf(tempPart);
		}

		// should never get here
		return null;
	}

	private TreePart getLeaf(TempPart tempPart) {
		Type partType = PART_TYPES_MAP.get(tempPart.getFunctionName());
		if (partType == null)
			throw new RequestParsingException("Function " + tempPart.getFunctionName() + " is unknown");
		String source = propertyQuery ? propertyName + "." + tempPart.getPropertyName() : tempPart.getPropertyName();
		TreePart part = new Part(source, partType, getDomainClass(), tempPart.shouldIgnoreCase());

		Class<?> partPropertyType = ((Part) part).getProperty().getLeafProperty().getType();

		addJpaParametersAndValues(tempPart, partType, partPropertyType);
		return part;
	}

	private void addJpaParametersAndValues(TempPart tempPart, Part.Type partType, Class<?> partPropertyType) {
		String[] strParams = preprocessParameterValue(partType, tempPart);
		List<Object> values = new ArrayList<Object>(strParams.length);
		for (String param : strParams) {
			values.add(getParsedObject(param, partPropertyType));
		}
		switch (partType) {
		case IN:
		case NOT_IN:
			jpaParameters.add(new JpaParameter(partPropertyType, jpaParameters.size()));
			parametersValues.add(values);
			break;
		default:
			parametersValues.addAll(values);
			if (partType.getNumberOfArguments() != values.size()) {
				throw new RequestParsingException("Function " + tempPart.getFunctionName() + " should have " + partType.getNumberOfArguments() + " parameters");
			}
			for (int i = 0; i < values.size(); i++) {
				jpaParameters.add(new JpaParameter(partPropertyType, jpaParameters.size()));
			}
		}

	}

	private String[] preprocessParameterValue(Type partType, TempPart tempPart) {
		String result[] = tempPart.getParameters();
		switch (partType) {
		case LIKE:
			for (int i = 0; i < tempPart.getParameters().length; i++) {
				result[i] = tempPart.getParameters()[i].replace("*", "%");
			}
			return result;
		default:
			return result;
		}
	}

	private Object getParsedObject(String sValue, Class<?> type) {
		try {
			return objectMapper.readValue("\"" + sValue + "\"", type);
		} catch (JsonParseException e) {
			logger.error(e.getMessage());
			throw new IllegalArgumentException(e.getMessage());
		} catch (JsonMappingException e) {
			logger.error(e.getMessage());
			throw new IllegalArgumentException(e.getMessage());
		} catch (IOException e) {
			logger.error(e.getMessage());
			throw new IllegalArgumentException(e.getMessage());
		}
	}

	private List<JpaParameter> getJpaParameters() {
		return jpaParameters;
	}

	public Class<?> getDomainClass() {
		return domainPersistentEntity.getType();
	}

}
