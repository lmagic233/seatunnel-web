/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.seatunnel.config.impl;

import org.apache.seatunnel.config.ConfigException.NotResolved;
import org.apache.seatunnel.config.ConfigException.UnresolvedSubstitution;
import org.apache.seatunnel.config.ConfigOrigin;
import org.apache.seatunnel.config.ConfigRenderOptions;
import org.apache.seatunnel.config.ConfigValue;
import org.apache.seatunnel.config.ConfigValueType;

import java.util.Collection;
import java.util.Collections;

/**
 * ConfigReference replaces ConfigReference (the older class kept for back
 * compat) and represents the ${} substitution syntax. It can resolve to any
 * kind of value.
 */
final class ConfigReference extends AbstractConfigValue implements Unmergeable {

    private final SubstitutionExpression expr;
    // the length of any prefixes added with relativized()
    private final int prefixLength;

    ConfigReference(ConfigOrigin origin, SubstitutionExpression expr) {
        this(origin, expr, 0);
    }

    private ConfigReference(ConfigOrigin origin, SubstitutionExpression expr, int prefixLength) {
        super(origin);
        this.expr = expr;
        this.prefixLength = prefixLength;
    }

    private NotResolved notResolved() {
        return new NotResolved(
                "need to Config#resolve(), see the API docs for Config#resolve(); substitution not resolved: " + this);
    }

    @Override
    public ConfigValueType valueType() {
        throw notResolved();
    }

    @Override
    public Object unwrapped() {
        throw notResolved();
    }

    @Override
    protected ConfigReference newCopy(ConfigOrigin newOrigin) {
        return new ConfigReference(newOrigin, expr, prefixLength);
    }

    @Override
    protected boolean ignoresFallbacks() {
        return false;
    }

    @Override
    public Collection<ConfigReference> unmergedValues() {
        return Collections.singleton(this);
    }

    // ConfigReference should be a firewall against NotPossibleToResolve going
    // further up the stack; it should convert everything to ConfigException.
    // This way it's impossible for NotPossibleToResolve to "escape" since
    // any failure to resolve has to start with a ConfigReference.
    @Override
    ResolveResult<? extends AbstractConfigValue> resolveSubstitutions(ResolveContext context, ResolveSource source) {
        ResolveContext newContext = context.addCycleMarker(this);
        AbstractConfigValue v;
        try {
            ResolveSource.ResultWithPath resultWithPath = source.lookupSubst(newContext, expr, prefixLength);
            newContext = resultWithPath.result.context;

            if (resultWithPath.result.value != null) {
                if (ConfigImpl.traceSubSituationsEnable()) {
                    ConfigImpl.trace(newContext.depth(), "recursively resolving "
                            + resultWithPath
                            + " which was the resolution of "
                            + expr + " against " + source);
                }

                ResolveSource recursiveResolveSource = new ResolveSource(
                        (AbstractConfigObject) resultWithPath.pathFromRoot.last(), resultWithPath.pathFromRoot);

                if (ConfigImpl.traceSubSituationsEnable()) {
                    ConfigImpl.trace(newContext.depth(), "will recursively resolve against " + recursiveResolveSource);
                }

                ResolveResult<? extends AbstractConfigValue> result = newContext.resolve(resultWithPath.result.value,
                        recursiveResolveSource);
                v = result.value;
                newContext = result.context;
            } else {
                ConfigValue fallback = context.options().getResolver().lookup(expr.path().render());
                v = (AbstractConfigValue) fallback;
            }
        } catch (NotPossibleToResolve e) {
            if (ConfigImpl.traceSubSituationsEnable()) {
                ConfigImpl.trace(newContext.depth(),
                        "not possible to resolve "
                                + expr + ", cycle involved: "
                                + e.traceString());
            }
            if (expr.optional()) {
                v = null;
            } else {
                throw new UnresolvedSubstitution(origin(), expr
                        + " was part of a cycle of substitutions involving "
                        + e.traceString(), e);
            }
        }

        if (v == null && !expr.optional()) {
            if (newContext.options().getAllowUnresolved()) {
                return ResolveResult.make(newContext.removeCycleMarker(this), this);
            }
            throw new UnresolvedSubstitution(origin(), expr.toString());
        }
        return ResolveResult.make(newContext.removeCycleMarker(this), v);
    }

    @Override
    ResolveStatus resolveStatus() {
        return ResolveStatus.UNRESOLVED;
    }

    // when you graft a substitution into another object,
    // you have to prefix it with the location in that object
    // where you grafted it; but save prefixLength so
    // system property and env variable lookups don't get
    // broken.
    @Override
    ConfigReference relativized(Path prefix) {
        SubstitutionExpression newExpr = expr.changePath(expr.path().prepend(prefix));
        return new ConfigReference(origin(), newExpr, prefixLength + prefix.length());
    }

    @Override
    protected boolean canEqual(Object other) {
        return other instanceof ConfigReference;
    }

    @Override
    public boolean equals(Object other) {
        // note that "origin" is deliberately NOT part of equality
        if (other instanceof ConfigReference) {
            return canEqual(other) && this.expr.equals(((ConfigReference) other).expr);
        }
        return false;
    }

    @Override
    public int hashCode() {
        // note that "origin" is deliberately NOT part of equality
        return expr.hashCode();
    }

    @Override
    protected void render(StringBuilder sb, int indent, boolean atRoot, ConfigRenderOptions options) {
        sb.append(expr.toString());
    }

    SubstitutionExpression expression() {
        return expr;
    }
}