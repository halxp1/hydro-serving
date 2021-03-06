package io.hydrosphere.serving.manager.model.api

import io.hydrosphere.serving.contract.model_contract.ModelContract
import io.hydrosphere.serving.contract.model_field.ModelField
import io.hydrosphere.serving.contract.model_signature.ModelSignature
import io.hydrosphere.serving.manager.model.Result.HError
import io.hydrosphere.serving.tensorflow.tensor_shape.TensorShapeProto
import io.hydrosphere.serving.tensorflow.types.DataType

package object validation {
  abstract class ValidationError(val message: String) extends HError

  class SignatureMissingError(val expectedSignature: String, val modelContract: ModelContract)
    extends ValidationError(
      s"Couldn't find '$expectedSignature' signature among [${modelContract.signatures.map(_.signatureName)}] signatures"
    ) {}

  class SignatureValidationError(
    val suberrors: Seq[ValidationError],
    val modelSignature: ModelSignature
  ) extends ValidationError(
      s"Errors while validating data for '${modelSignature.signatureName}' signature: ${suberrors.map(_.message).mkString("\n")}"
    ) {}

  class FieldMissingError(val expectedField: String)
    extends ValidationError(s"Couldn't find '$expectedField' field") {}

  class ComplexFieldValidationError(val suberrors: Seq[ValidationError], val field: ModelField)
    extends ValidationError(
      s"Errors while validating subfields for '${field.name}' field: ${suberrors.map(_.message).mkString("\n")}"
    )

  class IncompatibleFieldTypeError(val field: String, val expectedType: DataType)
    extends ValidationError(s"'$field' got data with incompatible type, expected $expectedType")

  class UnsupportedFieldTypeError(val expectedType: DataType)
    extends ValidationError(s"'$expectedType' is not supported")

  class IncompatibleFieldShapeError(val field: String, val expectedShape: Option[TensorShapeProto])
    extends ValidationError(s"'$field' got data with incompatible shape, expected $expectedShape")

  class InvalidFieldData[T](val actualClass: Class[T])
    extends ValidationError(s"Got data with incompatible type '$actualClass'")
}
