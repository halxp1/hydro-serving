package io.hydrosphere.serving.manager.service.model_build.builders

import com.spotify.docker.client.ProgressHandler
import io.hydrosphere.serving.manager.model._
import io.hydrosphere.serving.manager.model.db.{ModelBuild, ModelVersion}

trait ModelBuildService {
  val SCRIPT_VAL_MODEL_PATH = "MODEL_PATH"
  val SCRIPT_VAL_MODEL_TYPE = "MODEL_TYPE"
  val SCRIPT_VAL_MODEL_NAME = "MODEL_NAME"
  val SCRIPT_VAL_MODEL_VERSION = "MODEL_VERSION"

  def build(modelBuild: ModelBuild, script: String, progressHandler: ProgressHandler): HFResult[String]
}

trait ModelPushService {
  def getImageName(modelBuild: ModelBuild): String = {
    modelBuild.model.name
  }

  def push(modelRuntime: ModelVersion, progressHandler: ProgressHandler)
}

class EmptyModelPushService extends ModelPushService {
  override def push(modelRuntime: ModelVersion, progressHandler: ProgressHandler): Unit = {}
}

