package ml.combust.mleap.runtime.frame

import java.util.UUID

import ml.combust.mleap.core.Model
import ml.combust.mleap.core.types.{NodeShape, StructField, StructType}
import ml.combust.mleap.runtime.function.{FieldSelector, Selector, UserDefinedFunction}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

/** Companion class for transformer.
  */
object Transformer {
  /** Generate a unique name with a base string.
    *
    * @param base base string
    * @return unique name from base string
    */
  def uniqueName(base: String): String = s"${base}_${UUID.randomUUID().toString}"
}

/** Trait for implementing an MLeap transformer.
  */
trait Transformer extends AutoCloseable {
  /** Unique identifier for this transformer.
    */
  val uid: String

  /** Shape of inputs/outputs */
  val shape: NodeShape

  /** Model for this transformer */
  val model: Model

  /** Get the input schema of this transformer.
    *
    * The input schema is the recommended values to
    * pass in to this transformer.
    *
    * @return input schema
    */
  def inputSchema: StructType = {
    val fields = model.inputSchema.fields.map {
      case StructField(port, dataType) => StructField(shape.input(port).name, dataType)
    }

    StructType(fields).get
  }

  /** Get the output schema of this transformer.
    *
    * The outputs schema is the actual output datatype(s) of
    * this transformer that will be produced.
    *
    * @return output schema
    */
  def outputSchema: StructType = {
    val outputs = shape.outputs.values.map(_.port).toSet
    val fields = model.outputSchema.fields.filter(f => outputs.contains(f.name)).map {
      case StructField(port, dataType) => StructField(shape.output(port).name, dataType)
    }
    StructType(fields).get
  }

  /** Transform a builder using this MLeap transformer.
    *
    * @param builder builder to transform
    * @tparam FB underlying class of builder
    * @return try new builder with transformation applied
    */
  def transform[FB <: FrameBuilder[FB]](builder: FB): Try[FB]

  /** Transform a builder using this MLeap transformer.
    *
    * @param builder builder to transform
    * @param ec execution context to run operation on
    * @tparam FB underlying class of builder
    * @return try new builder with transformation applied
    */
  def transformAsync[FB <: FrameBuilder[FB]](builder: FB)
                                            (implicit ec: ExecutionContext): Future[FB] = {
    transform(builder) match {
      case Success(b) => Future.successful(b)
      case Failure(e) => Future.failed(e)
    }
  }

  /** Get the full schema of this transformer (inputs ++ outputs).
    *
    * @return full schema of this transformer
    */
  def schema: StructType = {
    StructType(inputSchema.fields ++ outputSchema.fields).get
  }

  override def close(): Unit = { /* do nothing by default */ }
}

trait BaseTransformer extends Transformer {
  val exec: UserDefinedFunction

  val inputs: Seq[String] = inputSchema.fields.map(_.name)
  val selectors: Seq[Selector] = inputs.map(FieldSelector)
}

trait SimpleTransformer extends BaseTransformer {
  val output: String = outputSchema.fields.head.name

  lazy val typedExec: UserDefinedFunction = exec.withInputs(inputSchema).withOutput(outputSchema)
  override def transform[FB <: FrameBuilder[FB]](builder: FB): Try[FB] = {
    builder.withColumn(output, selectors: _*)(typedExec)
  }
}

trait MultiTransformer extends BaseTransformer {
  val outputs: Seq[String] = outputSchema.fields.map(_.name)

  lazy val typedExec: UserDefinedFunction = exec.withInputs(inputSchema).withOutput(outputSchema)
  override def transform[FB <: FrameBuilder[FB]](builder: FB): Try[FB] = {
    builder.withColumns(outputs, selectors: _*)(typedExec)
  }
}
