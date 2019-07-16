/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.shardingsphere.core.optimize.statement.encrypt.condition.engine;

import com.google.common.base.Optional;
import lombok.RequiredArgsConstructor;
import org.apache.shardingsphere.core.exception.ShardingException;
import org.apache.shardingsphere.core.metadata.table.ShardingTableMetaData;
import org.apache.shardingsphere.core.optimize.statement.PredicateUtils;
import org.apache.shardingsphere.core.optimize.statement.encrypt.condition.EncryptCondition;
import org.apache.shardingsphere.core.parse.sql.segment.dml.WhereSegment;
import org.apache.shardingsphere.core.parse.sql.segment.dml.predicate.AndPredicate;
import org.apache.shardingsphere.core.parse.sql.segment.dml.predicate.PredicateSegment;
import org.apache.shardingsphere.core.parse.sql.segment.dml.predicate.SubqueryPredicateSegment;
import org.apache.shardingsphere.core.parse.sql.segment.dml.predicate.value.PredicateBetweenRightValue;
import org.apache.shardingsphere.core.parse.sql.segment.dml.predicate.value.PredicateCompareRightValue;
import org.apache.shardingsphere.core.parse.sql.segment.dml.predicate.value.PredicateInRightValue;
import org.apache.shardingsphere.core.parse.sql.statement.SQLStatement;
import org.apache.shardingsphere.core.parse.sql.statement.dml.WhereSegmentAvailable;
import org.apache.shardingsphere.core.rule.EncryptRule;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;

/**
 * Encrypt condition engine for where clause.
 *
 * @author zhangliang
 */
@RequiredArgsConstructor
public final class WhereClauseEncryptConditionEngine {
    
    private final EncryptRule encryptRule;

    private final ShardingTableMetaData shardingTableMetaData;

    /**
     * Create encrypt conditions.
     *
     * @param sqlStatement SQL statement
     * @return encrypt conditions
     */
    public List<EncryptCondition> createEncryptConditions(final SQLStatement sqlStatement) {
        if (!(sqlStatement instanceof WhereSegmentAvailable)) {
            return Collections.emptyList();
        }
        Optional<WhereSegment> whereSegment = ((WhereSegmentAvailable) sqlStatement).getWhere();
        if (!whereSegment.isPresent()) {
            return Collections.emptyList();
        }
        List<EncryptCondition> result = new LinkedList<>();
        for (AndPredicate each : whereSegment.get().getAndPredicates()) {
            result.addAll(createEncryptConditions(each, sqlStatement));
        }
        for (SubqueryPredicateSegment each : sqlStatement.findSQLSegments(SubqueryPredicateSegment.class)) {
            for (AndPredicate andPredicate : each.getAndPredicates()) {
                result.addAll(createEncryptConditions(andPredicate, sqlStatement));
            }
        }
        return result;
    }

    private Collection<EncryptCondition> createEncryptConditions(final AndPredicate andPredicate, final SQLStatement sqlStatement) {
        Collection<EncryptCondition> result = new LinkedList<>();
        Collection<Integer> stopIndexes = new HashSet<>();
        for (PredicateSegment predicate : andPredicate.getPredicates()) {
            if (stopIndexes.add(predicate.getStopIndex())) {
                Optional<EncryptCondition> condition = createEncryptCondition(predicate, sqlStatement);
                if (condition.isPresent()) {
                    result.add(condition.get());
                }
            }
        }
        return result;
    }
    
    private Optional<EncryptCondition> createEncryptCondition(final PredicateSegment predicateSegment, final SQLStatement sqlStatement) {
        Optional<String> tableName = PredicateUtils.findTableName(predicateSegment, sqlStatement, shardingTableMetaData);
        if (!tableName.isPresent() || !encryptRule.getEncryptorEngine().getShardingEncryptor(tableName.get(), predicateSegment.getColumn().getName()).isPresent()) {
            return Optional.absent();
        }
        return createEncryptCondition(predicateSegment, tableName.get());
    }

    private Optional<EncryptCondition> createEncryptCondition(final PredicateSegment predicateSegment, final String tableName) {
        if (predicateSegment.getRightValue() instanceof PredicateCompareRightValue) {
            PredicateCompareRightValue compareRightValue = (PredicateCompareRightValue) predicateSegment.getRightValue();
            return isSupportedOperator(compareRightValue.getOperator())
                    ? PredicateUtils.createCompareCondition(compareRightValue, tableName, predicateSegment) : Optional.<EncryptCondition>absent();
        }
        if (predicateSegment.getRightValue() instanceof PredicateInRightValue) {
            return PredicateUtils.createInCondition((PredicateInRightValue) predicateSegment.getRightValue(), tableName, predicateSegment);
        }
        if (predicateSegment.getRightValue() instanceof PredicateBetweenRightValue) {
            throw new ShardingException("The SQL clause 'BETWEEN...AND...' is unsupported in encrypt rule.");
        }
        return Optional.absent();
    }

    private boolean isSupportedOperator(final String operator) {
        return "=".equals(operator) || "<>".equals(operator) || "!=".equals(operator);
    }
}
