/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.midonet.api.filter;

import org.midonet.midolman.rules.RuleResult;
import org.midonet.cluster.data.rules.LiteralRule;

/**
 * Reject rule DTO
 */
public class RejectRule extends Rule {

    public RejectRule() {
        super();
    }

    public RejectRule(LiteralRule rule) {
        super(rule);
    }

    @Override
    public String getType() {
        return RuleType.Reject;
    }

    @Override
    public LiteralRule toData () {
        LiteralRule data = new LiteralRule(makeCondition(),
                RuleResult.Action.REJECT);
        super.setData(data);
        return data;
    }
}
