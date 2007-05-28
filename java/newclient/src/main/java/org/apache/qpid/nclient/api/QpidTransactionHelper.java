/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */
package org.apache.qpid.nclient.api;

/**
 * used as a helper class to support the Session class
 * This reduces the clutter and makes the session class
 * more readable and manageable.
 * 
 * An implementation of this interface should take a Session
 * interface as an argument in the constructor and scope all
 * operations for that session.
 */

public interface QpidTransactionHelper
{
	public void commit()throws QpidException;
	
	public void rollback()throws QpidException;
	
	public void recover()throws QpidException;
	
	public void resume()throws QpidException;
}
