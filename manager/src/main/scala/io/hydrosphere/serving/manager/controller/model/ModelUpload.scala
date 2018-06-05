package io.hydrosphere.serving.manager.controller.model

import java.nio.file.Path

import io.hydrosphere.serving.contract.model_contract.ModelContract
import io.hydrosphere.serving.manager.model.{HResult, Result}

import scala.reflect.ClassTag

case class ModelUpload(
  tarballPath: Path,
  name: Option[String] = None,
  modelType: Option[String] = None,
  contract: Option[ModelContract] = None,
  description: Option[String] = None,
  namespace: Option[String] = None
)

case class ModelDeploy(
  modelUpload: ModelUpload,
  runtimeName: String,
  runtimeVersion: String
)

object ModelUpload {

  def fromUploadEntities(entities: Seq[UploadedEntity]): HResult[ModelUpload] = {
    for {
      tarball <- extractRes[UploadTarball](entities).right
    } yield ModelUpload(
      name = extractOpt[UploadModelName](entities).map(_.modelName),
      modelType = extractOpt[UploadModelType](entities).map(_.modelType),
      contract = extractOpt[UploadContract](entities).map(_.modelContract),
      description = extractOpt[UploadDescription](entities).map(_.description),
      namespace = extractOpt[UploadNamespace](entities).map(_.namespace),
      tarballPath = tarball.path
    )
  }

  def extractRes[T](map: Seq[UploadedEntity])(implicit ct: ClassTag[T]): HResult[T] = {
    map.find(ct.runtimeClass.isInstance) match {
      case Some(field) =>
        field match {
          case ct(x) => Right(x)
          case x => Result.clientError(s"'$field' has incompatible type. Expected: ${ct.runtimeClass}, got ${x.getClass}")
        }
      case None => Result.clientError(s"'${ct.runtimeClass}' part is missing")
    }
  }

  def extractOpt[T](map: Seq[UploadedEntity])(implicit ct: ClassTag[T]): Option[T] = {
    extractRes[T](map).right.toOption
  }
}

object ModelDeploy {

  def fromUploadEntities(entities: Seq[UploadedEntity]): HResult[ModelDeploy] = {
    for {
      upload <- ModelUpload.fromUploadEntities(entities).right
      name <- ModelUpload.extractRes[DeployRuntimeName](entities).right
      version <- ModelUpload.extractRes[DeployRuntimeVersion](entities).right
    } yield ModelDeploy(
      modelUpload = upload,
      runtimeName = name.runtimeName,
      runtimeVersion = version.runtimeVersion
    )
  }
}