package inkblot.codegen

import inkblot.reasoning.VariableProperties
import org.apache.jena.query.Query

class SemanticObjectGenerator(private val query: Query, private val anchor: String, private val namespace: String, private val variableInfo: Map<String, VariableProperties>) {

    private val className = anchor.replaceFirstChar { if (it.isLowerCase()) it.uppercase() else it.toString() }
    private val synthesizer = QuerySynthesizer(query, anchor, variableInfo)
    private val changeNodeGenerator = ChangeNodeGenerator(synthesizer)

    //private val variableInfo: Map<String, VariableProperties> = ParameterAnalysis.deriveTypes(query, anchor).filter { it.key != anchor }

    private fun indent(content: String, l: Int): String {
        return content.lines().first() + "\n" + content.lines().drop(1).joinToString("\n").prependIndent(" ".repeat(4*l))
    }

    fun gen(): String {
        return pkg() + "\n\n" + imports() + "\n\n" + genFactory() + "\n\n" + genObject()
    }

    private fun genFactory() = """
        object ${className}Factory : SemanticObjectFactory<$className>() {
            override val query = "${query.toString().replace("\n", " ").replace(Regex("/\\s+/"), " ")}"
            override val anchor = "$anchor"
            
            ${indent(genFactoryCreate(), 3)}
            
            ${indent(genFactoryInstantiate(), 3)}
        }
        """.trimIndent()

    private fun genFactoryCreate() : String {
        val synthesizedCreateQuery = synthesizer.baseCreationUpdate()
        val constructorVars = variableInfo.mapValues { (v, props) ->
            if(props.isObjectReference) {
                when {
                    !props.functional -> "$v.map{ it.uri }"
                    props.nullable -> "$v?.uri"
                    else -> "$v.uri"
                }
            }
            else
                v
        }.values

        val constructorParamLine = (listOf("uri") + constructorVars).joinToString(", ")

        val safeInitObjRefBindings = variableInfo.filterValues { it.functional && !it.nullable && it.isObjectReference }.keys.map {
            "template.setIri(\"$it\", $it.uri)"
        }

        val safeInitDataBindings = variableInfo.filterValues { it.functional && !it.nullable && !it.isObjectReference }.keys.map {
            "template.setParam(\"$it\", ResourceFactory.createTypedLiteral($it))"
        }

        return """
            fun create(${genExternalConstructorArgs()}): $className {
                val uri = "$namespace$anchor" + Inkblot.freshSuffixFor("$anchor")

                // set non-null parameters and create object
                val template = ParameterizedSparqlString("$synthesizedCreateQuery")
                template.setIri("anchor", uri)
                ${indent((safeInitObjRefBindings + safeInitDataBindings).joinToString("\n"), 4)}
                
                val update = template.asUpdate()
                
                ${indent(genFactoryCreateInitNullable(), 4)}
               
                ${indent(genFactoryCreateInitNonFunctional(), 4)}
                
                Inkblot.changelog.add(CreateNode(update))
                return $className($constructorParamLine)
            }
        """.trimIndent()
    }

    private fun genFactoryCreateInitNullable(): String {
        val vars = variableInfo.filter { it.value.nullable && it.value.functional }

        if(vars.isEmpty())
            return ""

        val initializers = vars.mapValues { (v, props) ->
            val targetBinding = if(props.isObjectReference)
                "partialUpdate.setIri(\"v\", $v.uri)"
            else
                "partialUpdate.setParam(\"v\", ResourceFactory.createTypedLiteral($v))"

            """
                if($v != null) {
                    val partialUpdate = ParameterizedSparqlString("${synthesizer.initializerUpdate(v)}")
                    partialUpdate.setIri("anchor", uri)
                    $targetBinding
                    partialUpdate.asUpdate().forEach { update.add(it) }
                }
            """.trimIndent()
        }.values.joinToString("\n\n")

        return "// initialize nullable functional properties\n$initializers"
    }

    private fun genFactoryCreateInitNonFunctional(): String {
        val vars = variableInfo.filter { !it.value.functional }

        if(vars.isEmpty())
            return ""

        val initializers = vars.mapValues { (v, props) ->
            val targetBinding = if(props.isObjectReference)
                    "partialUpdate.setIri(\"$v\", it.uri)"
                else
                    "partialUpdate.setParam(\"$v\", ResourceFactory.createTypedLiteral(it))"

            """
                $v.forEach {
                    val partialUpdate = ParameterizedSparqlString("${synthesizer.initializerUpdate(v)}")
                    partialUpdate.setIri("$anchor", uri)
                    $targetBinding
                    partialUpdate.asUpdate().forEach { part -> update.add(part) }
                }
            """.trimIndent()
        }.values.joinToString("\n\n")

        return "// initialize non-functional properties\n$initializers"
    }

    private fun genFactoryInstantiate() : String {
        val constructorArgs = (listOf("uri") + variableInfo.keys).joinToString(", ")
        return """
            override fun instantiateSingleResult(lines: List<QuerySolution>): $className? {
                if(lines.isEmpty())
                    return null
                    
                val uri = lines.first().getResource("$anchor").uri
                    
                ${indent(genFactoryInstantiateFunctional(), 4)}

                ${indent(genFactoryInstantiateNonFunctional(), 4)}
                    
                return $className($constructorArgs) 
            }
        """.trimIndent()
    }

    private fun genFactoryInstantiateFunctional() : String {
        val functionalVars = variableInfo.filterValues { it.functional }

        if(functionalVars.isEmpty())
            return ""

        val assignments = functionalVars.mapValues { (v, props) ->
            when {
                props.isObjectReference && props.nullable -> "val $v = lines.first().getResource(\"$v\")?.uri"
                props.isObjectReference -> "val $v = lines.first().getResource(\"$v\").uri"
                props.nullable -> "val $v = Inkblot.types.literalToNullable${props.datatype}(lines.first().getLiteral(\"$v\"))"
                else -> "val $v = Inkblot.types.literalTo${props.datatype}(lines.first().getLiteral(\"$v\"))"
            }
        }.values.joinToString("\n")

        return "// for functional properties we can read the first only, as all others have to be the same\n$assignments"
    }

    private fun genFactoryInstantiateNonFunctional(): String {
        val nonFuncVars = variableInfo.filterValues { !it.functional }

        if(nonFuncVars.isEmpty())
            return ""

        val assignments = nonFuncVars.mapValues { (v, props) ->
            if(props.isObjectReference)
                "val $v = lines.mapNotNull { it.getResource(\"$v\")?.uri }.distinct()"
            else
                "val $v = lines.mapNotNull { Inkblot.types.literalToNullable${props.datatype}(it.getLiteral(\"$v\")) }.distinct()"
        }.values.joinToString("\n")

        return "// for higher cardinality properties, we have to collect all distinct values\n$assignments"
    }

    private fun genObject(): String {
        val constructorVars = variableInfo.keys.joinToString(", ")
        return """
            class $className internal constructor(${genInternalConstructorArgs()}) : SemanticObject(uri) {
                companion object {
                    fun create(${genExternalConstructorArgs()}) = ${className}Factory.create($constructorVars)
                    fun loadAll() = ${className}Factory.loadAll()
                    fun loadSelected(filter: String) = ${className}Factory.loadSelected(filter)
                    fun loadFromURI(uri: String) = ${className}Factory.loadFromURI(uri)
                }
                
                ${indent(genProperties(), 4)}
                
                // TODO: Merge
                
                // TODO: Custom delete if necessary
            }
        """.trimIndent()
    }

    private fun genExternalConstructorArgs(): String {
        return variableInfo.map { (v, info) ->
            when {
                info.functional && info.nullable -> "$v: ${info.datatype}?"
                info.functional -> "$v: ${info.datatype}"
                else -> "$v: List<${info.datatype}>"
            }
        }.joinToString(", ")
    }

    private fun genInternalConstructorArgs(): String {
        val args = mutableListOf("uri: String")

        variableInfo.forEach { (v, info) ->
            val arg = when {
                info.isObjectReference && info.functional && info.nullable -> "$v: String?"
                info.isObjectReference && info.functional -> "$v: String"
                info.isObjectReference -> "$v: List<String>"
                info.functional && info.nullable -> "$v: ${info.datatype}?"
                info.functional -> "$v: ${info.datatype}"
                else -> "$v: List<${info.datatype}>"
            }
            args.add(arg)
        }

        return args.joinToString(", ")
    }

    private fun genProperties() = variableInfo.keys.joinToString("\n\n") { genProperty(it) }

    private fun genProperty(varName: String): String {
        return if(variableInfo[varName]!!.isObjectReference)
            genObjectProperty(varName)
        else
            genDataProperty(varName)
    }

    private fun genDataProperty(varName: String): String {
        val varProps = variableInfo[varName]!!
        return if(varProps.functional) {
            if(varProps.nullable)
                genSingletNullableDataProperty(varName, varProps.datatype!!)
            else
                genSingletNonNullDataProperty(varName, varProps.datatype!!)
        } else {
            genMultiDataProperty(varName, varProps.datatype!!)
        }
    }

    private fun genSingletNonNullDataProperty(varName: String, datatype: String): String {
        return """
            var $varName: $datatype = $varName
                set(value) {
                    ${indent(genDeleteCheck(varName), 5)}

                    val newValueNode = ResourceFactory.createTypedLiteral(value).asNode()
                    val oldValueNode = ResourceFactory.createTypedLiteral(field).asNode()
                    ${indent(changeNodeGenerator.changeCN("uri", varName, "oldValueNode", "newValueNode"), 5) }
                    Inkblot.changelog.add(cn)
                    
                    field = value
                    markDirty()
                }
        """.trimIndent()
    }

    private fun genSingletNullableDataProperty(varName: String, datatype: String): String {
        return """
            var $varName: $datatype? = $varName
                set(value) {
                    ${indent(genDeleteCheck(varName), 5)}
                    
                    val oldValueNode = ResourceFactory.createTypedLiteral(field).asNode()
                    val newValueNode = ResourceFactory.createTypedLiteral(value).asNode()
                    if(value == null) {
                        // Unset value
                        ${indent(changeNodeGenerator.removeCN("uri", varName, "oldValueNode"), 6)}
                        Inkblot.changelog.add(cn)
                    }
                    else if(field == null) {
                        // Pure insertion
                        ${indent(changeNodeGenerator.addCN("uri", varName, "newValueNode"), 6)}
                        Inkblot.changelog.add(cn)
                    }
                    else {
                        // Update value
                        ${indent(changeNodeGenerator.changeCN("uri", varName, "oldValueNode", "newValueNode!!"), 6)}
                        Inkblot.changelog.add(cn)
                    }

                    field = value
                    markDirty()
                }
        """.trimIndent()
    }

    private fun genMultiDataProperty(varName: String, datatype: String): String {
        val valueNode = "ResourceFactory.createTypedLiteral(data).asNode()"
        return """
            private val inkblt_$varName = $varName.toMutableList()

            val $varName: List<$datatype>
                get() = inkblt_$varName

            fun ${varName}_add(data: $datatype) {
                ${indent(genDeleteCheck(varName), 4)}
                inkblt_$varName.add(data)
                
                ${indent(changeNodeGenerator.addCN("uri", varName, valueNode), 4)}
                Inkblot.changelog.add(cn)
                markDirty()
            }

            fun ${varName}_remove(data: $datatype) {
                ${indent(genDeleteCheck(varName), 4)}
                inkblt_$varName.remove(data)
                
                ${indent(changeNodeGenerator.removeCN("uri", varName, valueNode), 4)}
                Inkblot.changelog.add(cn)
                markDirty()
            }
        """.trimIndent()
    }

    private fun genObjectProperty(varName: String): String {
        val varProps = variableInfo[varName]!!
        return if(varProps.functional) {
            if(varProps.nullable)
                genSingletNullableObjectProperty(varName, varProps.datatype!!)
            else
                genSingletNonNullObjectProperty(varName, varProps.datatype!!)
        } else {
            genMultiObjectProperty(varName, varProps.datatype!!)
        }
    }

    private fun genSingletNonNullObjectProperty(varName: String, datatype: String): String {
        return """
            private var _inkbltRef_$varName: String = $varName
            var $varName: $datatype
                get() = $datatype.loadFromURI(_inkbltRef_$varName)
            set(value) {
                ${indent(genDeleteCheck(varName), 4)}

                ${indent(changeNodeGenerator.changeCN("uri", varName, "ResourceFactory.createResource(_inkbltRef_$varName).asNode()", "ResourceFactory.createResource(value.uri).asNode()"), 4)}
                Inkblot.changelog.add(cn)
                
                _inkbltRef_$varName = value.uri
                markDirty()
            }
        """.trimIndent()
    }

    private fun genSingletNullableObjectProperty(varName: String, datatype: String): String {
        return """
            private var _inkbltRef_$varName: String? = $varName
            var $varName: $datatype? = null
                get() {
                    return if(field == null && _inkbltRef_$varName != null)
                        ${datatype}Factory.loadFromURI(_inkbltRef_$varName!!)
                    else
                        field
                }
                set(value) {
                    ${indent(genDeleteCheck(varName), 5)}
                    field = value

                    val oldValueNode = ResourceFactory.createResource(_inkbltRef_$varName).asNode()
                    val newValueNode = ResourceFactory.createResource(value?.uri).asNode()

                    if(value == null) {
                        // Unset value
                        ${indent(changeNodeGenerator.removeCN("uri", varName, "oldValueNode"), 6)}
                        Inkblot.changelog.add(cn)
                    }
                    else if(_inkbltRef_$varName == null) {
                        // Pure insertion
                        ${indent(changeNodeGenerator.addCN("uri", varName, "newValueNode"), 6)}
                        Inkblot.changelog.add(cn)
                    }
                    else {
                        // Change value
                        ${indent(changeNodeGenerator.changeCN("uri", varName, "oldValueNode", "newValueNode"), 6)}
                        Inkblot.changelog.add(cn)
                    }
                    
                    _inkbltRef_$varName = value?.uri
                    markDirty()
                }
        """.trimIndent()
    }

    private fun genMultiObjectProperty(varName: String, datatype: String): String {
        val valueNode = "ResourceFactory.createResource(obj.uri).asNode()"
        return """
            private val _inkbltRef_$varName = $varName.toMutableSet()
            val $varName: List<$datatype>
                get() = _inkbltRef_$varName.map { ${datatype}Factory.loadFromURI(it) } // this is cached from DB so I hope it's fine?

            fun ${varName}_add(obj: $datatype) {
                ${indent(genDeleteCheck(varName), 4)}
                _inkbltRef_$varName.add(obj.uri)

                ${indent(changeNodeGenerator.addCN("uri", varName, valueNode), 4)}
                Inkblot.changelog.add(cn)
                markDirty()
            }

            fun ${varName}_remove(obj: $datatype) {
                ${indent(genDeleteCheck(varName), 4)}
                _inkbltRef_$varName.remove(obj.uri)

                ${indent(changeNodeGenerator.removeCN("uri", varName, valueNode), 4)}
                Inkblot.changelog.add(cn)
                markDirty()
            }
        """.trimIndent()
    }

    private fun genDeleteCheck(varName: String) = """
            if(deleted)
                throw Exception("Trying to set property '$varName' on deleted object <${'$'}uri>")
        """.trimIndent()

    private fun imports(): String {
        return """
            import inkblot.runtime.*
            import org.apache.jena.query.ParameterizedSparqlString
            import org.apache.jena.query.QuerySolution
            import org.apache.jena.rdf.model.ResourceFactory
        """.trimIndent()
    }

    private fun pkg() = "package gen\n"
}