/*
  Cryson
  
  Copyright 2011-2012 Björn Sperber (cryson@sperber.se)
  
  Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at
  
  http://www.apache.org/licenses/LICENSE-2.0
  
  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
*/

package se.sperber.cryson.service;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.apache.log4j.Logger;
import org.hibernate.OptimisticLockException;
import org.hibernate.StaleObjectStateException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.orm.hibernate3.HibernateOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import se.sperber.cryson.exception.CrysonEntityConflictException;
import se.sperber.cryson.exception.CrysonException;
import se.sperber.cryson.listener.CrysonListener;
import se.sperber.cryson.listener.ListenerNotificationBatch;
import se.sperber.cryson.serialization.CrysonSerializer;

import javax.annotation.PostConstruct;
import javax.ws.rs.*;
import javax.ws.rs.core.*;
import java.io.Serializable;
import java.text.ParseException;
import java.util.*;

@Service
@Path("/")
@Produces("application/json")
@PreAuthorize("isAuthenticated()")
public class CrysonFrontendService {

  @Autowired
  private DefaultListableBeanFactory defaultListableBeanFactory;

  @Autowired
  private CrysonService crysonService;

  @Autowired
  private CrysonSerializer crysonSerializer;

  private Set<CrysonListener> crysonListeners;

  private static final Logger logger = Logger.getLogger(CrysonFrontendService.class);

  @PostConstruct
  public void findListeners() {
    crysonListeners = new HashSet<CrysonListener>(defaultListableBeanFactory.getBeansOfType(CrysonListener.class).values());
  }
  
  @GET
  @Path("definition/{entity_name}")
  public Response getEntityDefinition(@PathParam("entity_name") String entityName) {
    try {
      return crysonService.getEntityDefinition(entityName);
    } catch(Throwable t) {
      return translateThrowable(t);
    }
  }

  @GET
  @Path("definitions")
  public Response getEntityDefinitions() {
    try {
      return crysonService.getEntityDefinitions();
    } catch(Throwable t) {
      return translateThrowable(t);
    }
  }

  @GET
  @Path("{entity_name}/{rawIds: [0-9,]+}")
  public Response getEntityById(@PathParam("entity_name") String entityName, @PathParam("rawIds") String rawIds, @QueryParam("fetch") String rawAssociationsToFetch) {
    try {
      Set<String> associationsToFetch = splitAssociationsToFetch(rawAssociationsToFetch);

      String[] stringIds = rawIds.split(",");
      if (stringIds.length == 1) {
        return crysonService.getEntityById(entityName, Long.parseLong(stringIds[0]), associationsToFetch);
      } else {
        List<Long> ids = new ArrayList<Long>(stringIds.length);
        for(int ix = 0;ix < stringIds.length;ix++) {
          ids.add(Long.parseLong(stringIds[ix]));
        }
        return crysonService.getEntitiesByIds(entityName, ids, associationsToFetch);
      }
    } catch(Throwable t) {
      return translateThrowable(t);
    }
  }

  @GET
  @Path("{entity_name}")
  public Response getEntitiesByExample(@PathParam("entity_name") String entityName, @QueryParam("example") String exampleJson, @QueryParam("fetch") String rawAssociationsToFetch) {
    try {
      Set<String> associationsToFetch = splitAssociationsToFetch(rawAssociationsToFetch);
      return crysonService.getEntitiesByExample(entityName, exampleJson, associationsToFetch);
    } catch(Throwable t) {
      return translateThrowable(t);
    }
  }

  @GET
  @Path("{entity_name}/all")
  public Response getAllEntities(@PathParam("entity_name") String entityName, @QueryParam("fetch") String rawAssociationsToFetch) {
    try {
      Set<String> associationsToFetch = splitAssociationsToFetch(rawAssociationsToFetch);
      return crysonService.getAllEntities(entityName, associationsToFetch);
    } catch(Throwable t) {
      return translateThrowable(t);
    }
  }

  @GET
  @Path("namedQuery/{query_name}")
  public Response getEntitiesByNamedQuery(@PathParam("query_name") String queryName, @Context UriInfo uriInfo, @QueryParam("fetch") String rawAssociationsToFetch) {
    try {
      Set<String> associationsToFetch = splitAssociationsToFetch(rawAssociationsToFetch);
      MultivaluedMap<String,String> queryParameters = uriInfo.getQueryParameters();
      queryParameters.remove("fetch");
      return crysonService.getEntitiesByNamedQuery(queryName, queryParameters, associationsToFetch);
    } catch(Throwable t) {
      return translateThrowable(t);
    }
  }

  @PUT
  @Path("{entity_name}")
  public Response createEntity(@Context UriInfo uriInfo, @Context HttpHeaders httpHeaders, @PathParam("entity_name") String entityName, String json) {
    try {
      ListenerNotificationBatch listenerNotificationBatch = new ListenerNotificationBatch(uriInfo, httpHeaders);
      Response response = crysonService.createEntity(entityName, json, listenerNotificationBatch);
      notifyCommit(listenerNotificationBatch);
      return response;
    } catch(Throwable t) {
      return translateThrowable(t);
    }
  }

  @POST
  @Path("commit")
  public Response commit(@Context UriInfo uriInfo, @Context HttpHeaders httpHeaders, String json) {
    try {
      ListenerNotificationBatch listenerNotificationBatch = new ListenerNotificationBatch(uriInfo, httpHeaders);
      JsonElement committedEntities = crysonSerializer.parse(json);
      crysonService.validatePermissions(committedEntities);
      List<Object> persistedEntites = new ArrayList<Object>();
      List<Object> updatedEntities = new ArrayList<Object>();
      JsonObject responseJsonObject = crysonService.commit(committedEntities, listenerNotificationBatch, persistedEntites, updatedEntities);
      crysonService.refreshEntities(responseJsonObject, listenerNotificationBatch, persistedEntites, updatedEntities);
      Response response = Response.ok(crysonSerializer.serializeTree(responseJsonObject)).build();

      notifyCommit(listenerNotificationBatch);
      return response;
    } catch(Throwable t) {
      return translateThrowable(t);
    }
  }
  
  public Response commitEntity(Object entity, UriInfo uriInfo, HttpHeaders httpHeaders) {
    try {
      ListenerNotificationBatch listenerNotificationBatch = new ListenerNotificationBatch(uriInfo, httpHeaders);
      Response response = crysonService.commitEntity(entity, listenerNotificationBatch);
      notifyCommit(listenerNotificationBatch);
      return response;
    } catch(Throwable t) {
      return translateThrowable(t);
    }
  }

  private void notifyCommit(ListenerNotificationBatch listenerNotificationBatch) {
    for (CrysonListener crysonListener : crysonListeners) {
      try {
        crysonListener.commitCompleted(listenerNotificationBatch);
      } catch(Throwable t) {
        logger.error("Cryson listener of class " + crysonListener.getClass().getSimpleName() + " threw after commit.", t);
      }
    }
  }

  private Response translateCrysonException(CrysonException e) {
    String serializedMessage = crysonSerializer.serializeWithoutAugmentation(e.getSerializableMessage());
    return Response.status(e.getStatusCode()).entity(serializedMessage).build();
  }
  
  private Response translateThrowable(Throwable t) {
    logger.error("Error", t);
    if (t instanceof CrysonException) {
      return translateCrysonException((CrysonException)t);
    } else if (t instanceof OptimisticLockException || t instanceof HibernateOptimisticLockingFailureException || t instanceof StaleObjectStateException) {
      return translateCrysonException(new CrysonEntityConflictException("Optimistic locking failed", t));
    } else if (t instanceof AccessDeniedException) {
      return Response.status(Response.Status.UNAUTHORIZED).entity(buildJsonMessage(t.getMessage())).build();
    } else if (t instanceof ParseException) {
      return Response.status(Response.Status.BAD_REQUEST).entity(buildJsonMessage(t.getMessage())).build();
    } else {
      return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(buildJsonMessage(t.getMessage())).build();
    }
  }

  private String buildJsonMessage(String message) {
    Map<String, Serializable> messageObject = new HashMap<String, Serializable>();
    if (message == null || message.equals("")) {
      messageObject.put("message", "Unclassified error");
    } else {
      messageObject.put("message", message);
    }
    return crysonSerializer.serializeWithoutAugmentation(messageObject);
  }

  private Set<String> splitAssociationsToFetch(String rawAssociationsToFetch) {
    if (rawAssociationsToFetch == null || rawAssociationsToFetch.equals("")) {
      return Collections.emptySet();
    }

    return new HashSet<String>(Arrays.asList(rawAssociationsToFetch.split(",")));
  }

}
