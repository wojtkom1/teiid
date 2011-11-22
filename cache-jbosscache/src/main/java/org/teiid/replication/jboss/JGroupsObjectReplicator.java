/*
 * JBoss, Home of Professional Open Source.
 * See the COPYRIGHT.txt file distributed with this work for information
 * regarding copyright ownership.  Some portions may be licensed
 * to Red Hat, Inc. under one or more contributor license agreements.
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
 * 02110-1301 USA.
 */

package org.teiid.replication.jboss;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Vector;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import org.jboss.as.clustering.jgroups.ChannelFactory;
import org.jgroups.Address;
import org.jgroups.Channel;
import org.jgroups.MembershipListener;
import org.jgroups.Message;
import org.jgroups.MessageListener;
import org.jgroups.Receiver;
import org.jgroups.ReceiverAdapter;
import org.jgroups.View;
import org.jgroups.blocks.GroupRequest;
import org.jgroups.blocks.MethodCall;
import org.jgroups.blocks.MethodLookup;
import org.jgroups.blocks.RequestOptions;
import org.jgroups.blocks.ResponseMode;
import org.jgroups.blocks.RpcDispatcher;
import org.jgroups.util.Promise;
import org.jgroups.util.RspList;
import org.jgroups.util.Util;
import org.teiid.Replicated;
import org.teiid.Replicated.ReplicationMode;
import org.teiid.logging.LogConstants;
import org.teiid.logging.LogManager;
import org.teiid.query.ObjectReplicator;
import org.teiid.query.ReplicatedObject;

public abstract class JGroupsObjectReplicator implements ObjectReplicator, Serializable {
	
	private static final long serialVersionUID = -6851804958313095166L;
	private static final String CREATE_STATE = "createState"; //$NON-NLS-1$
	private static final String BUILD_STATE = "buildState"; //$NON-NLS-1$
	private static final String FINISH_STATE = "finishState"; //$NON-NLS-1$

	private final class StreamingRunner implements Runnable {
		private final Object object;
		private final String stateId;
		private final JGroupsInputStream is;

		private StreamingRunner(Object object, String stateId, JGroupsInputStream is) {
			this.object = object;
			this.stateId = stateId;
			this.is = is;
		}

		@Override
		public void run() {
			try {
				((ReplicatedObject)object).setState(stateId, is);
				LogManager.logDetail(LogConstants.CTX_RUNTIME, "state set " + stateId); //$NON-NLS-1$
			} catch (Exception e) {
				LogManager.logError(LogConstants.CTX_RUNTIME, e, "error setting state " + stateId); //$NON-NLS-1$
			} finally {
				is.close();
			}
		}
	}

	private final static class ReplicatedInvocationHandler<S> implements
			InvocationHandler, Serializable, MessageListener, Receiver,
			MembershipListener {
		
		private static final long serialVersionUID = -2943462899945966103L;
		private final S object;
		private RpcDispatcher disp;
		private final HashMap<Method, Short> methodMap;
	    protected List<Address> remoteMembers = new ArrayList<Address>();
	    protected final transient Promise<Boolean> state_promise=new Promise<Boolean>();
	    
	    protected transient ThreadLocal<Promise<Boolean>> threadLocalPromise = new ThreadLocal<Promise<Boolean>>() {
	    	protected org.jgroups.util.Promise<Boolean> initialValue() {
	    		return new Promise<Boolean>();
	    	}
	    };
	    
		private ReplicatedInvocationHandler(S object,HashMap<Method, Short> methodMap) {
			this.object = object;
			this.methodMap = methodMap;
		}
		
		public void setDisp(RpcDispatcher disp) {
			this.disp = disp;
		}
		
		@Override
		public Object invoke(Object proxy, Method method, Object[] args)
				throws Throwable {
			Short methodNum = methodMap.get(method);
			if (methodNum == null || remoteMembers.isEmpty()) {
				if (methodNum != null) {
			    	Replicated annotation = method.getAnnotation(Replicated.class);
			    	if (annotation != null && annotation.remoteOnly()) {
			    		return null;
			    	}
				}
				try {
					return method.invoke(object, args);
				} catch (InvocationTargetException e) {
					throw e.getCause();
				}
			}
		    try {
		    	Replicated annotation = method.getAnnotation(Replicated.class);
		    	if (annotation.replicateState() != ReplicationMode.NONE) {
		    		Object result = null;
		    		try {
						result = method.invoke(object, args);
					} catch (InvocationTargetException e) {
						throw e.getCause();
					}
					List<Address> dests = null;
					synchronized (remoteMembers) {
						dests = new ArrayList<Address>(remoteMembers);
					}
					ReplicatedObject ro = (ReplicatedObject)object;
					String stateId = (String)args[0];
					if (annotation.replicateState() == ReplicationMode.PUSH) {
						LogManager.logDetail(LogConstants.CTX_RUNTIME, object, "replicating state", stateId); //$NON-NLS-1$
						JGroupsOutputStream oStream = new JGroupsOutputStream(disp, dests, stateId, (short)(methodMap.size() - 3));
						try {
							ro.getState(stateId, oStream);
						} finally {
							oStream.close();
						}
						LogManager.logTrace(LogConstants.CTX_RUNTIME, object, "sent state", stateId); //$NON-NLS-1$
				        return result;
					}
					if (result != null) {
						return result;
					}
					LogManager.logDetail(LogConstants.CTX_RUNTIME, object, "pulling state", stateId); //$NON-NLS-1$
					long timeout = annotation.timeout();
					threadLocalPromise.set(new Promise<Boolean>());
					boolean getState = this.disp.getChannel().getState(null, stateId, timeout);
					if (getState) {
						Boolean loaded = threadLocalPromise.get().getResult(timeout);
						if (Boolean.TRUE.equals(loaded)) {
							LogManager.logDetail(LogConstants.CTX_RUNTIME, object, "loaded", stateId); //$NON-NLS-1$
						} else {
							LogManager.logWarning(LogConstants.CTX_RUNTIME, object + " load error or timeout " + stateId); //$NON-NLS-1$
						}
					} else {
						LogManager.logInfo(LogConstants.CTX_RUNTIME, object + " first member or timeout exceeded " + stateId); //$NON-NLS-1$
					}
					LogManager.logTrace(LogConstants.CTX_RUNTIME, object, "sent state", stateId); //$NON-NLS-1$
			        return result;
				}
		        MethodCall call=new MethodCall(methodNum, args);
		        Vector<Address> dests = null;
		        if (annotation.remoteOnly()) {
					synchronized (remoteMembers) {
						dests = new Vector<Address>(remoteMembers);
					}
		        }
		        RspList responses = disp.callRemoteMethods(dests, call, new RequestOptions().setMode(annotation.asynch()?ResponseMode.GET_NONE:ResponseMode.GET_ALL).setTimeout(annotation.timeout()));
		        if (annotation.asynch()) {
			        return null;
		        }
		        List<Object> results = responses.getResults();
		        if (method.getReturnType() == boolean.class) {
		        	for (Object o : results) {
						if (!Boolean.TRUE.equals(o)) {
							return false;
						}
					}
		        	return true;
		        } else if (method.getReturnType() == Collection.class) {
		        	ArrayList<Object> result = new ArrayList<Object>();
		        	for (Object o : results) {
		        		result.addAll((Collection)o);
					}
		        	return results;
		        }
	        	return null;
		    } catch(Exception e) {
		        throw new RuntimeException(method + " " + args + " failed"); //$NON-NLS-1$ //$NON-NLS-2$
		    }
		}
		
		@Override
		public void viewAccepted(View newView) {
			if (newView.getMembers() != null) {
				synchronized (remoteMembers) {
					remoteMembers.removeAll(newView.getMembers());
					if (object instanceof ReplicatedObject && !remoteMembers.isEmpty()) {
						((ReplicatedObject)object).droppedMembers(new HashSet<Serializable>(remoteMembers));
					}
					remoteMembers.clear();
					remoteMembers.addAll(newView.getMembers());
					remoteMembers.remove(this.disp.getChannel().getAddress());
				}
			}
		}
		
		@Override
		public void setState(InputStream istream) {
			LogManager.logDetail(LogConstants.CTX_RUNTIME, object, "loading initial state"); //$NON-NLS-1$
			try {
				((ReplicatedObject)object).setState(istream);
				state_promise.setResult(Boolean.TRUE);
			} catch (Exception e) {
				state_promise.setResult(Boolean.FALSE);
				LogManager.logError(LogConstants.CTX_RUNTIME, e, "error loading initial state"); //$NON-NLS-1$
			} finally {
				Util.close(istream);
			}
		}
		
		@Override
		public void getState(OutputStream ostream) {
			LogManager.logDetail(LogConstants.CTX_RUNTIME, object, "getting initial state"); //$NON-NLS-1$
			try {
				((ReplicatedObject)object).getState(ostream);
			} catch (Exception e) {
				LogManager.logError(LogConstants.CTX_RUNTIME, e, "error gettting initial state"); //$NON-NLS-1$
			} finally {
				Util.close(ostream);
			}
		}
		
		public void setState(String stateId, InputStream istream) {
			LogManager.logDetail(LogConstants.CTX_RUNTIME, object, "loading state"); //$NON-NLS-1$
			try {
				((ReplicatedObject)object).setState(stateId, istream);
				threadLocalPromise.get().setResult(Boolean.TRUE);
			} catch (Exception e) {
				threadLocalPromise.get().setResult(Boolean.FALSE);
				LogManager.logError(LogConstants.CTX_RUNTIME, e, "error loading state"); //$NON-NLS-1$
			} finally {
				Util.close(istream);
			}
		}
		
		public void getState(String stateId, OutputStream ostream) {
			LogManager.logDetail(LogConstants.CTX_RUNTIME, object, "getting state"); //$NON-NLS-1$
			try {
				((ReplicatedObject)object).getState(stateId, ostream);
			} catch (Exception e) {
				LogManager.logError(LogConstants.CTX_RUNTIME, e, "error gettting state"); //$NON-NLS-1$
			} finally {
				Util.close(ostream);
			}
		}
	}
	
	private interface Streaming {
		void createState(String id);
		void buildState(String id, byte[] bytes);
		void finishState(String id);
	}

	//TODO: this should be configurable, or use a common executor
	private transient Executor executor = Executors.newCachedThreadPool();

	public JGroupsObjectReplicator(@SuppressWarnings("unused") String clusterName) {
	}
	
	public abstract ChannelFactory getChannelFactory();
	
	
	public void stop(Object object) {
		if (!Proxy.isProxyClass(object.getClass())) {
			return;
		}
		ReplicatedInvocationHandler<?> handler = (ReplicatedInvocationHandler<?>) Proxy.getInvocationHandler(object);
		Channel c = handler.disp.getChannel();
		handler.disp.stop();
		c.close();
	}
	
	@SuppressWarnings("unchecked")
	@Override
	public <T, S> T replicate(String mux_id,
			Class<T> iface, final S object, long startTimeout) throws Exception {
		Channel channel = getChannelFactory().createChannel(mux_id);
		Method[] methods = iface.getMethods();
		
		final HashMap<Method, Short> methodMap = new HashMap<Method, Short>();
		final ArrayList<Method> methodList = new ArrayList<Method>();
		
		for (Method method : methods) {
			if (method.getAnnotation(Replicated.class) == null) {
				continue;
			}
			methodList.add(method);
			methodMap.put(method, (short)(methodList.size() - 1));
		}
		
		//add in streaming methods
		Method createState = JGroupsObjectReplicator.Streaming.class.getMethod(CREATE_STATE, new Class<?>[] {String.class});
		methodList.add(createState);
		methodMap.put(createState, (short)(methodList.size() - 1));
		Method buildState = JGroupsObjectReplicator.Streaming.class.getMethod(BUILD_STATE, new Class<?>[] {String.class, byte[].class});
		methodList.add(buildState);
		methodMap.put(buildState, (short)(methodList.size() - 1));
		Method finishState = JGroupsObjectReplicator.Streaming.class.getMethod(FINISH_STATE, new Class<?>[] {String.class});
		methodList.add(finishState);
		methodMap.put(finishState, (short)(methodList.size() - 1));
		
        ReplicatedInvocationHandler<S> proxy = new ReplicatedInvocationHandler<S>(object, methodMap);
        /*
         * TODO: could have an object implement streaming
         * Override the normal handle method to support streaming
         */
		RpcDispatcher disp = new RpcDispatcher(channel, proxy, proxy, object) {
			Map<List<?>, JGroupsInputStream> inputStreams = new ConcurrentHashMap<List<?>, JGroupsInputStream>();
			@Override
			public Object handle(Message req) {
				Object      body=null;

		        if(req == null || req.getLength() == 0) {
		            if(log.isErrorEnabled()) log.error("message or message buffer is null"); //$NON-NLS-1$
		            return null;
		        }

		        try {
		            body=req_marshaller != null?
		                    req_marshaller.objectFromByteBuffer(req.getBuffer(), req.getOffset(), req.getLength())
		                    : req.getObject();
		        }
		        catch(Throwable e) {
		            if(log.isErrorEnabled()) log.error("exception marshalling object", e); //$NON-NLS-1$
		            return e;
		        }

		        if(!(body instanceof MethodCall)) {
		            if(log.isErrorEnabled()) log.error("message does not contain a MethodCall object"); //$NON-NLS-1$

		            // create an exception to represent this and return it
		            return  new IllegalArgumentException("message does not contain a MethodCall object") ; //$NON-NLS-1$
		        }

		        final MethodCall method_call=(MethodCall)body;

		        try {
		            if(log.isTraceEnabled())
		                log.trace("[sender=" + req.getSrc() + "], method_call: " + method_call); //$NON-NLS-1$ //$NON-NLS-2$

	                if(method_lookup == null)
	                    throw new Exception("MethodCall uses ID=" + method_call.getId() + ", but method_lookup has not been set"); //$NON-NLS-1$ //$NON-NLS-2$

		            if (method_call.getId() >= methodList.size() - 3) {
		            	Serializable address = req.getSrc();
		            	String stateId = (String)method_call.getArgs()[0];
		            	List<?> key = Arrays.asList(stateId, address);
		            	JGroupsInputStream is = inputStreams.get(key);
		            	if (method_call.getId() == methodList.size() - 3) {
		            		LogManager.logTrace(LogConstants.CTX_RUNTIME, object, "create state", stateId); //$NON-NLS-1$
		            		if (is != null) {
		            			is.receive(null);
		            		}
		            		is = new JGroupsInputStream();
		            		this.inputStreams.put(key, is);
		            		executor.execute(new StreamingRunner(object, stateId, is));
		            	} else if (method_call.getId() == methodList.size() - 2) {
		            		LogManager.logTrace(LogConstants.CTX_RUNTIME, object, "building state", stateId); //$NON-NLS-1$
		            		if (is != null) {
		            			is.receive((byte[])method_call.getArgs()[1]);
		            		}
		            	} else if (method_call.getId() == methodList.size() - 1) {
		            		LogManager.logTrace(LogConstants.CTX_RUNTIME, object, "finished state", stateId); //$NON-NLS-1$
		            		if (is != null) {
		            			is.receive(null);
		            		}
		            		this.inputStreams.remove(key);
		            	}  
		            	return null;
		            }
		            
	                Method m=method_lookup.findMethod(method_call.getId());
	                if(m == null)
	                    throw new Exception("no method found for " + method_call.getId()); //$NON-NLS-1$
	                method_call.setMethod(m);
		            
	            	return method_call.invoke(server_obj);
		        }
		        catch(Throwable x) {
		            return x;
		        }
			}
		};
		
		proxy.setDisp(disp);
        disp.setMethodLookup(new MethodLookup() {
            public Method findMethod(short id) {
                return methodList.get(id);
            }
        });
        
		T replicatedProxy = (T) Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(), new Class[] {iface}, proxy);
		boolean success = false;
		try {
			channel.connect(mux_id);
			if (object instanceof ReplicatedObject) {
				((ReplicatedObject)object).setLocalAddress(channel.getAddress());
				boolean getState = channel.getState(null, startTimeout);
				if (getState) {
					Boolean loaded = proxy.state_promise.getResult(startTimeout);
					if (Boolean.TRUE.equals(loaded)) {
						LogManager.logDetail(LogConstants.CTX_RUNTIME, object, "loaded"); //$NON-NLS-1$
					} else {
						LogManager.logWarning(LogConstants.CTX_RUNTIME, object + " load error or timeout"); //$NON-NLS-1$
					}
				} else {
					LogManager.logInfo(LogConstants.CTX_RUNTIME, object + " first member or timeout exceeded"); //$NON-NLS-1$
				}
			}
			success = true;
			return replicatedProxy;
		} finally {
			if (!success) {
				channel.close();
			}
		}
	}
	
}