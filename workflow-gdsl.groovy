/*
 * The MIT License
 *
 * Copyright (c) 2015 CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

/*
A Jenkins workflow script that generates a somewhat working gdsl file that can be used
to get code completion on workflow steps in Groovy files in IntelliJ IDEA.

Run the script as a workflow (sandbox turned off), and put the archived file into your classpath in a IDEA
Groovy project. Make sure to also have any required plugins in the classpath as well.

WARNING! This is a hack filled with assumptions and guesses, do not run in your production instance
 */
import com.cloudbees.groovy.cps.NonCPS
import hudson.ExtensionList
import hudson.model.Describable
import org.jenkinsci.plugins.workflow.cps.GlobalVariable
import org.jenkinsci.plugins.workflow.cps.Snippetizer
import org.jenkinsci.plugins.workflow.steps.AbstractStepDescriptorImpl
import org.jenkinsci.plugins.workflow.steps.Step
import org.jenkinsci.plugins.workflow.steps.StepDescriptor
import org.kohsuke.stapler.ClassDescriptor
import org.kohsuke.stapler.DataBoundConstructor
import org.kohsuke.stapler.DataBoundSetter

import java.lang.reflect.Constructor
import java.lang.reflect.Type

@NonCPS
String typeExpr(Type type, boolean describablesAsMaps = false) {
    if (type instanceof Class) {
        if (type.canonicalName == 'java.lang.Void') {
            return 'void'
        } else {
            if (describablesAsMaps && (Describable.isAssignableFrom(type) || type.name == 'jenkins.tasks.SimpleBuildStep')) { //SimpleBuildStep is not a describable
                return 'Map'
            } else {
                return type.canonicalName
            }
        }
    } else {
        return type.typeName
    }
}

@NonCPS
Map<String, String> getStepConstructorParams(Class<? extends Step> clazz) {
    Map<String, String> ctrs = [:]
    Constructor<?> dbc = clazz.constructors.find { def c ->
        return (c.getAnnotation(DataBoundConstructor.class) != null)
    }
    if (dbc != null) {
        String[] names = ClassDescriptor.loadParameterNames(dbc);
        def parameterTypes = dbc.getParameterTypes()
        if (names.length == parameterTypes.length) {
            for (int i = 0; i < names.length; i++) {
                ctrs.put(names[i], "'${this.typeExpr(parameterTypes[i], true)}'")
            }
        } else {
            for (int i = 0; i < parameterTypes.length; i++) {
                ctrs.put("arg${i}", "'${this.typeExpr(parameterTypes[i], true)}'")
            }
        }
    }
    return ctrs
}

@NonCPS
boolean hasOptionalParams(Class<? extends Step> clazz) {
    if (clazz.fields.find {
        return it.getAnnotation(DataBoundSetter.class) != null
    } != null) {
        return true
    }
    return clazz.methods.find {
        return it.getAnnotation(DataBoundSetter.class) != null
    } != null
}

@NonCPS
Map<String,String> findOptionalParams(Class<? extends Step> clazz) {
    Map<String, String> params = [:]
    for(def field : clazz.fields) {
        if (field.getAnnotation(DataBoundSetter.class) != null) {
            params.put(field.name, this.typeExpr(field.genericType))
        }
    }
    for (def method : clazz.methods) {
        if (method.getAnnotation(DataBoundSetter.class) != null) {
            String name = method.name
            if (name.startsWith('set')) {
                StringBuilder str = new StringBuilder(name);
                str.replace(0, 3, "")
                str.replace(0, 1, "${str.charAt(0)}".toLowerCase(Locale.ENGLISH))
                name = str.toString()
            }
            params.put(name, this.typeExpr(method.parameterTypes[0], true))
        }
    }
    return params
}

@NonCPS
String guessReturnType(StepDescriptor descr) {
    if (descr instanceof AbstractStepDescriptorImpl) {
        def executionType = descr.getExecutionType()
        def method = executionType.declaredMethods.find { a -> a.name == 'run' && a.parameterTypes.length <= 0 }
        if (method != null) {
            return this.typeExpr(method.genericReturnType)
        }
    }
    return 'void'
}

@NonCPS
void generateSteps(Collection<? extends StepDescriptor> stepDescriptors, List<String> scriptContext, List<String> nodeContext) {
    for (def desc : stepDescriptors) {
        def params = this.getStepConstructorParams(desc.clazz)
        def opts = this.findOptionalParams(desc.clazz)
        String retType = this.guessReturnType(desc)
        boolean requiresNode = desc.requiredContext.contains(hudson.FilePath)
        boolean takesClosure = desc.takesImplicitBlockArgument()
        String description = desc.displayName
        if (desc.isAdvanced()) {
            description = "Advanced/Deprecated " + description
        }
        if (params.size() <= 1) {
            def fixedParams = params
            if (takesClosure) {
                fixedParams = params + ['body': 'Closure']
            }
            String contr = "method(name: '${desc.functionName}', type: '${retType}', params: ${fixedParams}, doc: '${description}')"
            if (requiresNode) {
                nodeContext.add(contr)
            } else {
                scriptContext.add(contr)
            }
        }
        if (!opts.isEmpty() || params.size() > 1) {
            def paramsMap = [:]
            if (takesClosure) {
                paramsMap.put('body', 'Closure')
            }
            StringBuilder namedParamsS = new StringBuilder()
            for (def p : params) {
                namedParamsS.append("parameter(name: '${p.key}', type: ${p.value}), ")
            }
            for (def p : opts) {
                namedParamsS.append("parameter(name: '${p.key}', type: '${p.value}'), ")
            }
            String contr
            if (takesClosure) {
                contr = "method(name: '${desc.functionName}', type: '${retType}', params: [body:Closure], namedParams: [${namedParamsS.toString()}], doc: '${desc.displayName}')"
            } else {
                contr = "method(name: '${desc.functionName}', type: '${retType}', namedParams: [${namedParamsS.toString()}], doc: '${desc.displayName}')"
            }
            if (requiresNode) {
                nodeContext.add(contr)
            } else {
                scriptContext.add(contr)
            }
        }
    }
}

@NonCPS
void generateVars(Iterable<GlobalVariable> vars, List<String> scriptContext, List<String> nodeContext) {
    for(GlobalVariable variable : vars) {
        Object value = variable.getValue(this)
        if (value != null) {
            String contr = "property(name: '${variable.name}', type: '${value.getClass().canonicalName}')"
            scriptContext.add(contr)
        }
    }
}

@NonCPS
void generate(List<String> scriptContext, List<String> nodeContext) {
    Snippetizer snippetizer = ExtensionList.lookup(Snippetizer).get(0)
    this.generateSteps(snippetizer.getStepDescriptors(false), scriptContext, nodeContext)
    this.generateSteps(snippetizer.getStepDescriptors(true), scriptContext, nodeContext)
    this.generateVars(snippetizer.getGlobalVariables(), scriptContext, nodeContext)
}

List<String> scriptContext = []
List<String> nodeContext = []

this.generate(scriptContext, nodeContext)

String gdsl = """
//The global script scope
def ctx = context(scope: scriptScope())

contributor(ctx) {
${scriptContext.join('\n')}
}

//Steps that require a node context
def nodeCtx = context(scope: closureScope())

contributor(nodeCtx) {
    def call = enclosingCall('node')
    if (call) {
${nodeContext.join('\n')}
    }
}
"""

echo gdsl

node("") {
    writeFile(file: "workflow.gdsl", text: gdsl)
    archive("workflow.gdsl")
}