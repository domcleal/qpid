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
package org.apache.qpidity;


/**
 * MethodHandler is a stateful handler that aggregates frames into
 * method segments and dispatches the resulting method. It does not
 * accept any segment type other than Frame.METHOD.
 *
 * @author Rafael H. Schloming
 */

class MethodHandler<C extends DelegateResolver<C>> extends TypeSwitch<C>
{

    public MethodHandler()
    {
        map(Frame.METHOD, new SegmentAssembler<C>(new MethodDispatcher<C>()));
    }

}
