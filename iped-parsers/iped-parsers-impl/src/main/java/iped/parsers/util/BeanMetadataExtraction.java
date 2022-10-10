package iped.parsers.util;

import java.beans.BeanInfo;
import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.extractor.ParsingEmbeddedDocumentExtractor;
import org.apache.tika.metadata.HttpHeaders;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.ParseContext;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.xml.sax.ContentHandler;

import iped.data.IItem;
import iped.parsers.standard.StandardParser;
import iped.properties.BasicProps;
import iped.properties.ExtraProperties;
import iped.utils.DateUtil;
import iped.utils.EmptyInputStream;

public class BeanMetadataExtraction {
	String prefix;
	String mimeType;
	String nameProperty;
	int expandChildBeansLevel = 0;
	
	ExpressionParser elparser;

    HashMap<Object, EmbeddedItem> parentMap = new HashMap<Object, EmbeddedItem>();

	ArrayList<Class> beanClassesToExtract=new ArrayList<Class>();
	HashMap<Class, List<String>> excludeProperties=new HashMap<Class, List<String>>();
	HashMap<Class, String> nameProperties=new HashMap<Class, String>();
	HashMap<Class, String> referencedQuery=new HashMap<Class, String>();

	HashMap<Object[], String> propertyNameMapping=new HashMap<Object[], String>();//the object is an array of dimension 2 with the class as the first element and the propery name String as the second
	HashMap<Class, ArrayList<String[]>> transformationMapping=new HashMap<Class, ArrayList<String[]>>();//the object is an array of dimension 2 with the class as the first element and the metadata name String as the second
	
	private int level;

	public BeanMetadataExtraction(String prefix, String mimeType) {
		this.prefix = prefix;
		this.mimeType = mimeType;
		this.nameProperty="name";
		elparser = new SpelExpressionParser();
	}

	public void addPropertyExclusion(Class c, String propName) {
		List<String> props = excludeProperties.get(c);
		if(props==null) {
			props = new ArrayList<String>();
			excludeProperties.put(c, props);
		}
		props.add(propName);
	}

	public void registerClassNameProperty(Class c, String propName) {
		nameProperties.put(c, propName);
	}

	public void registerClassNameReferencedQuery(Class c, String propName) {
		referencedQuery.put(c, propName);
	}

    public void extractEmbedded(int seq, ParseContext context, Metadata metadata, ContentHandler handler, Object bean) throws IOException {
    	extractEmbedded(seq, context, metadata, null, handler, bean, -1);    	
    }
    
    class ChildParams{
    	Object value;
    	PropertyDescriptor pd;
    	
    	public ChildParams(Object value, PropertyDescriptor pd) {
    		this.value = value;
    		this.pd = pd;
    	}
    }
    
    protected boolean extractEmbedded(int seq, ParseContext context, Metadata metadata, PropertyDescriptor parentPd, ContentHandler handler, Object bean, int parentSeq) throws IOException {
        EmbeddedDocumentExtractor extractor = context.get(EmbeddedDocumentExtractor.class,
                new ParsingEmbeddedDocumentExtractor(context));
        if (extractor.shouldParseEmbedded(metadata)) {
             try {
                 Metadata entryMetadata = new Metadata();
                 entryMetadata.set(StandardParser.INDEXER_CONTENT_TYPE, mimeType);
                 entryMetadata.set(HttpHeaders.CONTENT_TYPE, mimeType);
                 entryMetadata.set(ExtraProperties.DECODED_DATA, Boolean.TRUE.toString());
                 entryMetadata.set("bean:className", bean.getClass().getCanonicalName());
                 
                 ArrayList<ChildParams> children = new ArrayList<ChildParams>();
                 
                 Object[] colObj = null;
                 if(bean instanceof Collection) {
                	 colObj = ((Collection) bean).toArray();
                 }
                 if(bean.getClass().isArray()) {
                	 colObj = (Object[]) bean;
                 }
                 if(bean instanceof Map) {
                	 colObj = ((Map) bean).entrySet().toArray();
                 }
                 
                 if(colObj == null) {
             		String resolvedNameProp = nameProperties.get(bean.getClass());
             		if(resolvedNameProp==null) {
             			resolvedNameProp = nameProperty;
             		}
             		String resolvedReferencedQuery = referencedQuery.get(bean.getClass());
               	    if(resolvedReferencedQuery!=null) {
               			entryMetadata.add(ExtraProperties.LINKED_ITEMS, parseQuery(resolvedReferencedQuery, bean));
               	    }
               	    
               	    List<String[]> transformations = getTransformationMapping(bean.getClass());
               	    if(transformations!=null) {
                   	    for (Iterator iterator = transformations.iterator(); iterator.hasNext();) {
    						String[] strings = (String[]) iterator.next();
    						entryMetadata.add(strings[0], parseQuery(strings[1], bean));
    					}
               	    }
                	 
                     for (PropertyDescriptor pd : Introspector.getBeanInfo(bean.getClass()).getPropertyDescriptors()) {
                    	 List<String> exclProps = excludeProperties.get(bean.getClass());
                    	 if(exclProps!=null && exclProps.contains(pd.getName())) {
                    		 continue;
                    	 }
                      	  if (pd.getReadMethod() != null && !"class".equals(pd.getName())) {
                        		Object value = null;
                        		try {
                               	    value = pd.getReadMethod().invoke(bean);
                        		}catch (Exception e) {
                        			e.printStackTrace();
      								continue;                        		
                        		}
                        		
                           	    if(pd.getDisplayName().equals(resolvedNameProp)) {
                           	    	String name = value.toString().replace("/", "_");
                           	    	entryMetadata.add(TikaCoreProperties.TITLE, name);//adds the name property without prefix
                           	    	entryMetadata.add(TikaCoreProperties.RESOURCE_NAME_KEY, name);
                          		}

                           	    if(value!=null) {
                           	    	String metadataName = getPropertyNameMapping(bean.getClass(), pd.getDisplayName());
                           	    	if(metadataName==null) {
                               	    	metadataName = pd.getDisplayName();
                               	    	if(prefix!=null && prefix.length()>0) {
                               	    		metadataName = prefix+":"+metadataName;    		                       	 	                       	  		
                               	    	}
                           	    	}

                           	    	if(isBean(value)) {
                                		children.add(new ChildParams(value, pd));
        								//this.extractEmbedded(seq, context, entryMetadata, pd, handler, value);
        							}else {
                               	    	if(value instanceof Date) {
            								entryMetadata.add(metadataName , DateUtil.dateToString((Date)value));
                               	    	}else {
                               	    		entryMetadata.add(metadataName, value.toString());
                              	    	}
        							}
                          	    }
                     	  }
                     }
                 } else {
                	 if(colObj.length<=0) {
                		 return false;
                	 }
            	     String metadataName = parentPd.getDisplayName();
            	     if(prefix!=null && prefix.length()>0) {
           	    		metadataName = prefix+":"+metadataName;    		                       	 	                       	  		
           	    	 }
           	    	 String name = metadataName.replace("/", "_");
           	    	 entryMetadata.add(TikaCoreProperties.TITLE, name);//adds the name property without prefix
           	    	 entryMetadata.add(TikaCoreProperties.RESOURCE_NAME_KEY, name);

                     for (int i = 0; i < colObj.length; i++) {
            			Object value = colObj[i];
                		if(isBean(value)) {
                    		children.add(new ChildParams(value, parentPd));
    						//this.extractEmbedded(seq, context, entryMetadata, parentPd, handler, colObj[i]);
                		} else {
                   	    	if(value instanceof Date) {
                   	    		entryMetadata.add(metadataName , DateUtil.dateToString((Date)value));
                   	    	}else {
                   	    		entryMetadata.add(metadataName, value.toString());
                  	    	}
                		}
					 }                	 
                 }
                 
                 if(children.size()>0) {
                     //entryMetadata.set(BasicProps.HASCHILD, "true");
                     entryMetadata.set(ExtraProperties.EMBEDDED_FOLDER, "true");
                 }

                 entryMetadata.set(ExtraProperties.PARENT_VIRTUAL_ID, Integer.toString(parentSeq));
                 entryMetadata.set(ExtraProperties.ITEM_VIRTUAL_ID, Integer.toString(seq));
                 extractor.parseEmbedded(new EmptyInputStream(), handler, entryMetadata, true);
                 EmbeddedItem addedItem = context.get(EmbeddedItem.class);


                 int childSeq = seq;
                 int count=0;
                 if(children.size()>0) {
                	 for (Iterator<ChildParams> iterator = children.iterator(); iterator.hasNext();) {
                    	ChildParams cp = iterator.next();
                    	childSeq++;
     					if(this.extractEmbedded(childSeq, context, entryMetadata, cp.pd, handler, cp.value, seq)) {
     						count++;
     					}
     				}
                 }
                 if(children.size()>0 && count<=0) {//real number of children added                	 
                	 IItem item = (IItem)addedItem.getObj();
                	 //entryMetadata = item.getMetadata();
                     //entryMetadata.set(BasicProps.HASCHILD, "false");
                     item.setIsDir(false);
                 }

             }catch (Exception e) {
				e.printStackTrace();
             }
    		 return true;
        }else {
   		 return false;
        }
    }
    
    private String parseQuery(String resolvedReferencedQuery, Object value) {
    	ArrayList<String> variables = new ArrayList<>();
    	EvaluationContext context = new StandardEvaluationContext(value);
    	
    	String result = resolvedReferencedQuery;
    	int i = result.indexOf("${");
    	while(i>0) {
    		int end = result.indexOf("}",i+2);
    		String varString = result.substring(i+2,end);
    		variables.add(varString);
    		i = result.indexOf("${",i+2);
    	}
    	
    	for (Iterator iterator = variables.iterator(); iterator.hasNext();) {
			String var = (String) iterator.next();
			result = result.replace("${"+var+"}", elparser.parseExpression(var).getValue(context).toString());
		}
		
		return result;
	}

	public boolean isBean(Object value) {
   	    BeanInfo beanInfo;
		try {
			beanInfo = Introspector.getBeanInfo(value.getClass());
	   	    if(beanInfo.getPropertyDescriptors().length>1 
	   	    		&& !(value instanceof String)
	   	    		&& !(value instanceof Date)) {
	   	    	return true;
	   	    }
		} catch (IntrospectionException e) {
			return false;
		}
   	    return false;
    }

	public String getNameProperty() {
		return nameProperty;
	}

	public void setNameProperty(String nameProperty) {
		this.nameProperty = nameProperty;
	}
	
	public void registerPropertyNameMapping(Class beanClass, String propertyName, String metadataPropName) {
		Object[] key = new Object[2];
		key[0]=beanClass;
		key[1]=propertyName;
		propertyNameMapping.put(key, metadataPropName);
	}

	public String getPropertyNameMapping(Class beanClass, String propertyName) {
		Object[] key = new Object[2];
		key[0]=beanClass;
		key[1]=propertyName;
		return propertyNameMapping.get(key);		
	}

	public void registerTransformationMapping(Class beanClass, String metadataPropName, String transformationExpression) {
		String[] value = new String[2];
		value[0]=metadataPropName;
		value[1]=transformationExpression;
		
		ArrayList<String[]> l = transformationMapping.get(value);
		if(l==null) {
			l=new ArrayList<String[]>();
			transformationMapping.put(beanClass, l);
		}
		l.add(value);
	}

	public List<String[]> getTransformationMapping(Class beanClass) {
		return transformationMapping.get(beanClass);		
	}
}
