import bikes.AppSpaceBike
import bikes.Bike
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import inkblot.codegen.SemanticObjectGenerator
import inkblot.reasoning.ParameterAnalysis
import inkblot.reasoning.VariableProperties
import org.apache.jena.query.ParameterizedSparqlString

class Inkblt : CliktCommand() {
    override fun run() = Unit
}

class Analyze: CliktCommand(help="Analyze a SPARQL query") {
    private val query: String by argument(help="SPARQL select query to analyze")
    private val anchor by option(help="anchor variable of query")
    override fun run() {
        org.apache.jena.query.ARQ.init()

        val q = ParameterizedSparqlString(query)
        q.setNsPrefix("bk", "http://rec0de.net/ns/bike#")

        val queryObj = q.asQuery()
        val anchorOrDefault = if(anchor == null) queryObj.resultVars.first() else anchor!!

        ParameterAnalysis.deriveTypes(q.asQuery(), anchorOrDefault)
    }
}

class Generate: CliktCommand(help="Generate semantic object for statement") {
    private val query: String by argument(help="SPARQL select query for object")
    private val anchor by option(help="anchor variable of query")

    override fun run() {
        org.apache.jena.query.ARQ.init()

        val q = ParameterizedSparqlString(query)
        q.setNsPrefix("bk", "http://rec0de.net/ns/bike#")
        val queryObj = q.asQuery()
        val anchorOrDefault = if(anchor == null) queryObj.resultVars.first() else anchor!!

        val variableInfo = mutableMapOf(
            "mfg" to VariableProperties(true, true, "Int", "<http://rec0de.net/ns/bike#mfgDate>"),
            "fw" to VariableProperties(false, true, "Wheel", null, true),
            "bw" to VariableProperties(true, true, "Wheel", null, true),
            "bells" to VariableProperties(true, false, "Bell", null, true)
        )

        val generator = SemanticObjectGenerator(queryObj, anchorOrDefault, "http://rec0de.net/ns/bike#", variableInfo)

        print(generator.gen())
    }
}

class Playground: CliktCommand(help="Execute playground environment") {
    override fun run() {
        org.apache.jena.query.ARQ.init()

        val bikes = Bike.loadAll()

        println("Loading bikes from data store")

        bikes.forEach { bike ->
            println("Bike: ${bike.uri}, ${bike.mfgDate}" )
            println("Front Wheel: ${bike.frontWheel.uri}, ${bike.frontWheel.diameter}, ${bike.frontWheel.mfgDate}, ${bike.frontWheel.mfgName.joinToString(", ")}")
            if(bike.backWheel != null) {
                val bw = bike.backWheel!!
                println("Back Wheel: ${bw.uri}, ${bw.diameter}, ${bw.mfgDate}, ${bw.mfgName.joinToString(", ")}")
            }
            bike.bells.forEach { bell ->
                println("Bell: ${bell.color}")
            }
            println()
        }

        //val newBike = Bike.create(2007, Wheel.loadAll().first(),null, emptyList())

        //newBike.bells_remove()
        //Bike.loadFromURI("http://rec0de.net/ns/bike#bike-4yi7b5a-e2dosvpz").delete()
        //Inkblot.commit()

        println("Unicycles:")
        val unicycles = Bike.loadSelected("!bound(?bw)")
        unicycles.forEach {
            println(it.uri)
        }
        println()

        val bikeA = AppSpaceBike(Bike.loadFromURI("http://rec0de.net/ns/bike#mountainbike"))
        val bikeB = AppSpaceBike(Bike.loadFromURI("http://rec0de.net/ns/bike#bike3"))
        //bikeB.removeAllBells()
        //Inkblot.commit()

        bikeA.ride("the supermarket", false)
        println()
        bikeB.ride("your destiny", true)
        println()
        bikeB.ride("the far away mountains", false)
    }
}

fun main(args: Array<String>) = Inkblt().subcommands(Analyze(), Playground(), Generate()).main(args)