/*
 * Licensed to Hewlett-Packard Development Company, L.P. under one
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
*/
package com.hp.score.lang.tests.operation;

import com.hp.score.events.EventConstants;
import com.hp.score.events.ScoreEvent;
import com.hp.score.lang.entities.CompilationArtifact;

import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.Serializable;
import java.net.URI;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Date: 11/14/2014
 *
 * @author Bonczidai Levente
 */
public class SimpleFlowTest extends SystemsTestsParent{

    private static final long DEFAULT_TIMEOUT = 20000;

    @Test (timeout = DEFAULT_TIMEOUT)
    public void testCompileAndRunSubFlowBasic() throws Exception {
        URI resource = getClass().getResource("/yaml/simple_flow.yaml").toURI();
        URI operations = getClass().getResource("/yaml/operation.yaml").toURI();

        List<File> path = Arrays.asList(new File(operations));
        CompilationArtifact compilationArtifact = compiler.compileFlow(new File(resource), path);

        Map<String, Serializable> userInputs = new HashMap<>();
        userInputs.put("input1", "-2");
        userInputs.put("time_zone_as_string", "+2");
        ScoreEvent event = triggerOperation(compilationArtifact, userInputs);
        Assert.assertEquals(EventConstants.SCORE_FINISHED_EVENT, event.getEventType());
    }

}