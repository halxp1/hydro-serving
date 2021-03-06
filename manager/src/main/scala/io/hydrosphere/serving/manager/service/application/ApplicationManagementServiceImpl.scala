package io.hydrosphere.serving.manager.service.application

import java.util.concurrent.atomic.AtomicReference

import cats.data.EitherT
import cats.implicits._
import io.hydrosphere.serving.contract.model_contract.ModelContract
import io.hydrosphere.serving.contract.model_signature.ModelSignature
import io.hydrosphere.serving.grpc.{AuthorityReplacerInterceptor, Header, Headers}
import io.hydrosphere.serving.manager.ApplicationConfig
import io.hydrosphere.serving.manager.controller.application._
import io.hydrosphere.serving.manager.model.Result.ClientError
import io.hydrosphere.serving.manager.model.Result.Implicits._
import io.hydrosphere.serving.manager.model.api.TensorExampleGenerator
import io.hydrosphere.serving.manager.model.api.json.TensorJsonLens
import io.hydrosphere.serving.manager.model.api.ops.ModelSignatureOps
import io.hydrosphere.serving.manager.model.api.tensor_builder.SignatureBuilder
import io.hydrosphere.serving.manager.model.db._
import io.hydrosphere.serving.manager.model.{db, _}
import io.hydrosphere.serving.manager.repository.{ApplicationRepository, RuntimeRepository}
import io.hydrosphere.serving.manager.service.clouddriver.CloudDriverService
import io.hydrosphere.serving.manager.service.environment.{AnyEnvironment, EnvironmentManagementService}
import io.hydrosphere.serving.manager.service.internal_events.InternalManagerEventsPublisher
import io.hydrosphere.serving.manager.service.model_version.ModelVersionManagementService
import io.hydrosphere.serving.manager.service.runtime.RuntimeManagementService
import io.hydrosphere.serving.manager.service.service.{CreateServiceRequest, ServiceManagementService}
import io.hydrosphere.serving.manager.util.TensorUtil
import io.hydrosphere.serving.monitoring.monitoring.ExecutionInformation.ResponseOrError
import io.hydrosphere.serving.monitoring.monitoring.{ExecutionError, ExecutionInformation, ExecutionMetadata, MonitoringServiceGrpc}
import io.hydrosphere.serving.profiler.profiler.DataProfilerServiceGrpc
import io.hydrosphere.serving.tensorflow.api.model.ModelSpec
import io.hydrosphere.serving.tensorflow.api.predict.{PredictRequest, PredictResponse}
import io.hydrosphere.serving.tensorflow.api.prediction_service.PredictionServiceGrpc
import io.hydrosphere.serving.tensorflow.tensor.{TensorProto, TypedTensorFactory}
import io.hydrosphere.serving.tensorflow.tensor_shape.TensorShapeProto
import io.hydrosphere.serving.tensorflow.types.DataType
import org.apache.logging.log4j.scala.Logging
import spray.json.JsObject

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

private case class ExecutionUnit(
  serviceName: String,
  servicePath: String,
  stageInfo: StageInfo,
)

private case class StageInfo(
  applicationRequestId: Option[String],
  signatureName: String,
  applicationId: Long,
  modelVersionId: Option[Long],
  stageId: String,
  applicationNamespace: Option[String],
  dataProfileFields: DataProfileFields = Map.empty
)

class ApplicationManagementServiceImpl(
  applicationRepository: ApplicationRepository,
  modelVersionManagementService: ModelVersionManagementService,
  environmentManagementService: EnvironmentManagementService,
  serviceManagementService: ServiceManagementService,
  grpcClient: PredictionServiceGrpc.PredictionServiceStub,
  grpcClientForMonitoring: MonitoringServiceGrpc.MonitoringServiceStub,
  grpcClientForProfiler: DataProfilerServiceGrpc.DataProfilerServiceStub,
  internalManagerEventsPublisher: InternalManagerEventsPublisher,
  applicationConfig: ApplicationConfig,
  runtimeService: RuntimeManagementService
)(implicit val ex: ExecutionContext) extends ApplicationManagementService with Logging {

  type FutureMap[T] = Future[Map[Long, T]]

  //TODO REMOVE!
  private def sendToDebug(responseOrError: ResponseOrError, predictRequest: PredictRequest, executionUnit: ExecutionUnit): Unit = {
    if (applicationConfig.shadowingOn) {
      val execInfo = ExecutionInformation(
        metadata = Option(ExecutionMetadata(
          applicationId = executionUnit.stageInfo.applicationId,
          stageId = executionUnit.stageInfo.stageId,
          modelVersionId = executionUnit.stageInfo.modelVersionId.getOrElse(-1),
          signatureName = executionUnit.stageInfo.signatureName,
          applicationRequestId = executionUnit.stageInfo.applicationRequestId.getOrElse(""),
          requestId = executionUnit.stageInfo.applicationRequestId.getOrElse(""), //todo fetch from response,
          applicationNamespace = executionUnit.stageInfo.applicationNamespace.getOrElse(""),
          dataTypes = executionUnit.stageInfo.dataProfileFields
        )),
        request = Option(predictRequest),
        responseOrError = responseOrError
      )

      grpcClientForMonitoring
        .withOption(AuthorityReplacerInterceptor.DESTINATION_KEY, CloudDriverService.MONITORING_NAME)
        .analyze(execInfo)
        .onComplete {
          case Failure(thr) =>
            logger.warn("Can't send message to the monitoring service", thr)
          case _ =>
            Unit
        }

      grpcClientForProfiler
        .withOption(AuthorityReplacerInterceptor.DESTINATION_KEY, CloudDriverService.PROFILER_NAME)
        .analyze(execInfo)
        .onComplete {
          case Failure(thr) =>
            logger.warn("Can't send message to the data profiler service", thr)
          case _ => Unit
        }
    }
  }

  private def getHeaderValue(header: Header): Option[String] = Option(header.contextKey.get())

  private def getCurrentExecutionUnit(unit: ExecutionUnit, modelVersionIdHeaderValue: AtomicReference[String]): ExecutionUnit = Try({
    Option(modelVersionIdHeaderValue.get()).map(_.toLong)
  }).map(s => unit.copy(stageInfo = unit.stageInfo.copy(modelVersionId = s)))
    .getOrElse(unit)


  private def getLatency(latencyHeaderValue: AtomicReference[String]): Try[TensorProto] = {
    Try({
      Option(latencyHeaderValue.get()).map(_.toLong)
    }).map(v => TensorProto(
      dtype = DataType.DT_INT64,
      int64Val = Seq(v.getOrElse(0)),
      tensorShape = Some(TensorShapeProto(dim = Seq(TensorShapeProto.Dim(1))))
    ))
  }

  def serve(unit: ExecutionUnit, request: PredictRequest, tracingInfo: Option[RequestTracingInfo]): HFResult[PredictResponse] = {
    val verificationResults = request.inputs.map {
      case (name, tensor) => name -> TensorUtil.verifyShape(tensor)
    }

    val errors = verificationResults.filter {
      case (_, t) => t.isLeft
    }.mapValues(_.left.get)

    if (errors.isEmpty) {
      val modelVersionIdHeaderValue = new AtomicReference[String](null)
      val latencyHeaderValue = new AtomicReference[String](null)

      var requestBuilder = grpcClient
        .withOption(AuthorityReplacerInterceptor.DESTINATION_KEY, unit.serviceName)
        .withOption(Headers.XServingModelVersionId.callOptionsClientResponseWrapperKey, modelVersionIdHeaderValue)
        .withOption(Headers.XEnvoyUpstreamServiceTime.callOptionsClientResponseWrapperKey, latencyHeaderValue)

      if (tracingInfo.isDefined) {
        val tr = tracingInfo.get
        requestBuilder = requestBuilder
          .withOption(Headers.XRequestId.callOptionsKey, tr.xRequestId)

        if (tr.xB3requestId.isDefined) {
          requestBuilder = requestBuilder
            .withOption(Headers.XB3TraceId.callOptionsKey, tr.xB3requestId.get)
        }

        if (tr.xB3SpanId.isDefined) {
          requestBuilder = requestBuilder
            .withOption(Headers.XB3ParentSpanId.callOptionsKey, tr.xB3SpanId.get)
        }
      }

      val verifiedInputs = verificationResults.mapValues(_.right.get)
      val verifiedRequest = request.copy(inputs = verifiedInputs)

      requestBuilder
        .predict(verifiedRequest)
        .transform(
          response => {
            val latency = getLatency(latencyHeaderValue)
            val res = if (latency.isSuccess) {
              response.addInternalInfo(
                "system.latency" -> latency.get
              )
            } else {
              response
            }

            sendToDebug(ResponseOrError.Response(res), verifiedRequest, getCurrentExecutionUnit(unit, modelVersionIdHeaderValue))
            Result.ok(response)
          },
          thr => {
            logger.error("Can't send message to GATEWAY_KAFKA", thr)
            sendToDebug(ResponseOrError.Error(ExecutionError(thr.toString)), verifiedRequest, getCurrentExecutionUnit(unit, modelVersionIdHeaderValue))
            thr
          }
        )
    } else {
      Future.successful(
        Result.errors(
          errors.map {
            case (name, err) =>
              ClientError(s"Shape verification error for input $name: $err")
          }.toSeq
        )
      )
    }
  }

  def servePipeline(units: Seq[ExecutionUnit], data: PredictRequest, tracingInfo: Option[RequestTracingInfo]): HFResult[PredictResponse] = {
    //TODO Add request id for step
    val empty = Result.okF(PredictResponse(outputs = data.inputs))
    units.foldLeft(empty) {
      case (a, b) =>
        EitherT(a).flatMap { resp =>
          val request = PredictRequest(
            modelSpec = Some(
              ModelSpec(
                signatureName = b.servicePath
              )
            ),
            inputs = resp.outputs
          )
          EitherT(serve(b, request, tracingInfo))
        }.value
    }
  }

  def serveApplication(application: Application, request: PredictRequest, tracingInfo: Option[RequestTracingInfo]): HFResult[PredictResponse] = {
    application.executionGraph.stages match {
      case stage :: Nil if stage.services.lengthCompare(1) == 0 => // single stage with single service
        request.modelSpec match {
          case Some(servicePath) =>
            //TODO change to real ID
            val stageId = ApplicationStage.stageId(application.id, 0)
            val modelVersionId = application.executionGraph.stages.headOption.flatMap(
              _.services.headOption.flatMap(_.serviceDescription.modelVersionId))

            val stageInfo = StageInfo(
              modelVersionId = modelVersionId,
              applicationRequestId = tracingInfo.map(_.xRequestId), // TODO get real traceId
              applicationId = application.id,
              signatureName = servicePath.signatureName,
              stageId = stageId,
              applicationNamespace = application.namespace,
              dataProfileFields =  stage.dataProfileFields
            )
            val unit = ExecutionUnit(
              serviceName = stageId,
              servicePath = servicePath.signatureName,
              stageInfo = stageInfo
            )
            serve(unit, request, tracingInfo)
          case None => Result.clientErrorF("ModelSpec in request is not specified")
        }
      case stages => // pipeline
        val execUnits = stages.zipWithIndex.map {
          case (stage, idx) =>
            stage.signature match {
              case Some(signature) =>
                val vers = stage.services.headOption.flatMap(_.serviceDescription.modelVersionId)
                val stageInfo = StageInfo(
                  //TODO will be wrong modelVersionId during blue-green
                  //TODO Get this value from sidecar or in sidecar
                  modelVersionId = vers,
                  applicationRequestId = tracingInfo.map(_.xRequestId), // TODO get real traceId
                  applicationId = application.id,
                  signatureName = signature.signatureName,
                  stageId = ApplicationStage.stageId(application.id, idx),
                  applicationNamespace = application.namespace,
                  dataProfileFields =  stage.dataProfileFields
                )
                Result.ok(
                  ExecutionUnit(
                    serviceName = ApplicationStage.stageId(application.id, idx),
                    servicePath = stage.services.head.signature.get.signatureName, // FIXME dirty hack to fix service signatures
                    stageInfo = stageInfo
                  )
                )
              case None => Result.clientError(s"$stage doesn't have a signature")
            }
        }

        Result.sequence(execUnits) match {
          case Left(err) => Result.errorF(err)
          case Right(units) =>
            servePipeline(units, request, tracingInfo)
        }
    }
  }

  def serveGrpcApplication(data: PredictRequest, tracingInfo: Option[RequestTracingInfo]): HFResult[PredictResponse] = {
    data.modelSpec match {
      case Some(modelSpec) =>
        applicationRepository.getByName(modelSpec.name).flatMap {
          case Some(app) =>
            serveApplication(app, data, tracingInfo)
          case None => Future.failed(new IllegalArgumentException(s"Application '${modelSpec.name}' is not found"))
        }
      case None => Future.failed(new IllegalArgumentException("ModelSpec is not defined"))
    }
  }

  def serveJsonApplication(jsonServeRequest: JsonServeRequest, tracingInfo: Option[RequestTracingInfo]): HFResult[JsObject] = {
    getApplication(jsonServeRequest.targetId).flatMap {
      case Right(application) =>
        val signature = application.contract.signatures
          .find(_.signatureName == jsonServeRequest.signatureName)
          .toHResult(
            ClientError(s"Application ${jsonServeRequest.targetId} doesn't have a ${jsonServeRequest.signatureName} signature")
          )

        val ds = signature.right.map { sig =>
          new SignatureBuilder(sig).convert(jsonServeRequest.inputs).right.map { tensors =>
            PredictRequest(
              modelSpec = Some(
                ModelSpec(
                  name = application.name,
                  signatureName = jsonServeRequest.signatureName,
                  version = None
                )
              ),
              inputs = tensors.mapValues(_.toProto)
            )
          }
        }

        ds match {
          case Left(err) => Result.errorF(err)
          case Right(Left(tensorError)) => Result.clientErrorF(s"Tensor validation error: $tensorError")
          case Right(Right(request)) =>
            serveApplication(application, request, tracingInfo).map { result =>
              result.right.map(responseToJsObject)
            }
        }
      case Left(error) =>
        Result.errorF(error)
    }
  }

  override def getApplication(appId: Long): HFResult[Application] = {
    applicationRepository.get(appId).map {
      case Some(app) => Result.ok(app)
      case None => Result.clientError(s"Can't find application with ID $appId")
    }
  }

  def allApplications(): Future[Seq[Application]] = {
    applicationRepository.all()
  }

  def findVersionUsage(versionId: Long): Future[Seq[Application]] = {
    allApplications().map { apps =>
      apps.filter { app =>
        app.executionGraph.stages.exists { stage =>
          stage.services.exists { service =>
            service.serviceDescription.modelVersionId.contains(versionId)
          }
        }
      }
    }
  }

  def generateInputsForApplication(appId: Long, signatureName: String): HFResult[JsObject] = {
    getApplication(appId).map { result =>
      result.right.flatMap { app =>
        app.contract.signatures.find(_.signatureName == signatureName) match {
          case Some(signature) =>
            val data = TensorExampleGenerator(signature).inputs
            Result.ok(TensorJsonLens.mapToJson(data))
          case None => Result.clientError(s"Can't find signature '$signatureName")
        }
      }
    }
  }

  def createApplication(
    name: String,
    namespace: Option[String],
    executionGraph: ExecutionGraphRequest,
    kafkaStreaming: Seq[ApplicationKafkaStream]
  ): HFResult[Application] = executeWithSync {
    val keys = for {
      stage <- executionGraph.stages
      service <- stage.services
    } yield {
      service.toDescription
    }
    val keySet = keys.toSet

    val f = for {
      graph <- EitherT(inferGraph(executionGraph))
      contract <- EitherT(inferAppContract(name, graph))
      app <- EitherT(composeAppF(name, namespace, graph, contract, kafkaStreaming))

      services <- EitherT(serviceManagementService.fetchServicesUnsync(keySet).map(Result.ok))
      existedServices = services.map(_.toServiceKeyDescription)
      _ <- EitherT(startServices(keySet -- existedServices))

      createdApp <- EitherT(applicationRepository.create(app).map(Result.ok))
    } yield {
      internalManagerEventsPublisher.applicationChanged(createdApp)
      createdApp
    }
    f.value
  }

  def deleteApplication(id: Long): HFResult[Application] =
    executeWithSync {
      getApplication(id).flatMap {
        case Right(application) =>
          val keysSet = application.executionGraph.stages.flatMap(_.services.map(_.serviceDescription)).toSet
          applicationRepository.delete(id)
            .flatMap { _ =>
              removeServiceIfNeeded(keysSet, id)
                .map { _ =>
                  internalManagerEventsPublisher.applicationRemoved(application)
                  Result.ok(application)
                }
            }
        case Left(error) =>
          Result.errorF(error)
      }
    }

  def updateApplication(
    id: Long,
    name: String,
    namespace: Option[String],
    executionGraph: ExecutionGraphRequest,
    kafkaStreaming: Seq[ApplicationKafkaStream]
  ): HFResult[Application] = {
    executeWithSync {
      val res = for {
        oldApplication <- EitherT(getApplication(id))

        graph <- EitherT(inferGraph(executionGraph))
        contract <- EitherT(inferAppContract(name, graph))
        newApplication <- EitherT(composeAppF(name, namespace, graph, contract, kafkaStreaming, id))

        keysSetOld = oldApplication.executionGraph.stages.flatMap(_.services.map(_.serviceDescription)).toSet
        keysSetNew = executionGraph.stages.flatMap(_.services.map(_.toDescription)).toSet

        _ <- EitherT(removeServiceIfNeeded(keysSetOld -- keysSetNew, id))
        _ <- EitherT(startServices(keysSetNew -- keysSetOld))

        _ <- EitherT(applicationRepository.update(newApplication).map(Result.ok))
      } yield {
        internalManagerEventsPublisher.applicationChanged(newApplication)
        newApplication
      }
      res.value
    }
  }

  private def executeWithSync[A](func: => HFResult[A]): HFResult[A] = {
    applicationRepository.getLockForApplications().flatMap { lockInfo =>
      func andThen {
        case Success(r) =>
          applicationRepository.returnLockForApplications(lockInfo)
            .map(_ => r)
        case Failure(f) =>
          applicationRepository.returnLockForApplications(lockInfo)
            .map(_ => Result.internalError(f, "executeWithSync failed"))
      }
    }
  }

  private def startServices(keysSet: Set[ServiceKeyDescription]): HFResult[Seq[Service]] = {
    logger.debug(keysSet)
    serviceManagementService.fetchServicesUnsync(keysSet).flatMap { services =>
      val toAdd = keysSet -- services.map(_.toServiceKeyDescription)
      Result.traverseF(toAdd.toSeq) { key =>
        serviceManagementService.addService(
          CreateServiceRequest(
            serviceName = key.toServiceName(),
            runtimeId = key.runtimeId,
            configParams = None,
            environmentId = key.environmentId,
            modelVersionId = key.modelVersionId
          )
        )
      }
    }
  }

  private def removeServiceIfNeeded(keysSet: Set[ServiceKeyDescription], applicationId: Long): HFResult[Seq[Service]] = {
    val servicesF = for {
      apps <- applicationRepository.getKeysNotInApplication(keysSet, applicationId)
      keysSetOld = apps.flatMap(_.executionGraph.stages.flatMap(_.services.map(_.serviceDescription))).toSet
      services <- serviceManagementService.fetchServicesUnsync(keysSet -- keysSetOld)
    } yield services

    servicesF.flatMap { services =>
      Future.traverse(services) { service =>
        serviceManagementService.deleteService(service.id)
      }.map(Result.sequence)
    }
  }

  private def composeAppF(name: String, namespace: Option[String], graph: ApplicationExecutionGraph, contract: ModelContract, kafkaStreaming: Seq[ApplicationKafkaStream], id: Long = 0) = {
    Result.okF(
      Application(
        id = id,
        name = name,
        namespace = namespace,
        contract = contract,
        executionGraph = graph,
        kafkaStreaming = kafkaStreaming.toList
      )
    )
  }

  private def inferGraph(executionGraphRequest: ExecutionGraphRequest): HFResult[ApplicationExecutionGraph] = {
    val appStages =
      executionGraphRequest.stages match {
        case singleStage :: Nil if singleStage.services.lengthCompare(1) == 0 =>
          inferSimpleApp(singleStage) // don't perform checks
        case stages =>
          inferPipelineApp(stages)
      }
    EitherT(appStages).map { stages =>
      ApplicationExecutionGraph(stages.toList)
    }.value
  }

  private def inferSimpleApp(singleStage: ExecutionStepRequest): HFResult[Seq[ApplicationStage]] = {
    val service = singleStage.services.head
    service.modelVersionId match {
      case Some(vId) =>
        val f = for {
          version <- EitherT(modelVersionManagementService.get(vId))
          runtime <- EitherT(runtimeService.get(service.runtimeId))
          environment <- EitherT(environmentManagementService.get(service.environmentId.getOrElse(AnyEnvironment.id)))
          signed <- EitherT(createDetailedServiceDesc(service, version, runtime, environment, None))
        } yield Seq(
          ApplicationStage(
            services = List(signed.copy(weight = 100)), // 100 since this is the only service in the app
            signature = None,
            dataProfileFields = signed.modelVersion.dataProfileTypes.getOrElse(Map.empty)
          )
        )
        f.value
      case None => Result.clientErrorF(s"$service doesn't have a modelversion")
    }
  }

  private def inferPipelineApp(stages: Seq[ExecutionStepRequest]): HFResult[Seq[ApplicationStage]] = {
    Result.sequenceF {
      stages.zipWithIndex.map {
        case (stage, id) =>
          val f = for {
            services <- EitherT(inferServices(stage.services))
            stageSigs <- EitherT(Future.successful(inferStageSignature(services)))
          } yield {
            ApplicationStage(
              services = services.toList,
              signature = Some(stageSigs.withSignatureName(id.toString)),
              dataProfileFields = mergeServiceDataProfilingTypes(services)
            )
          }
          f.value
      }
    }
  }

  private def mergeServiceDataProfilingTypes(services: Seq[DetailedServiceDescription]): DataProfileFields = {
    val maps = services.map { s =>
      s.modelVersion.dataProfileTypes.getOrElse(Map.empty)
    }
    maps.reduce((a, b) => a ++ b)
  }

  def inferServices(services: List[ServiceCreationDescription]): HFResult[Seq[DetailedServiceDescription]] = {
    Result.sequenceF {
      services.map { service =>
        service.modelVersionId match {
          case Some(vId) =>
            val f = for {
              version <- EitherT(modelVersionManagementService.get(vId))
              runtime <- EitherT(runtimeService.get(service.runtimeId))
              signature <- EitherT(findSignature(version, service.signatureName))
              environment <- EitherT(environmentManagementService.get(service.environmentId.getOrElse(AnyEnvironment.id)))
              signed <- EitherT(createDetailedServiceDesc(service, version, runtime, environment, Some(signature)))
            } yield signed
            f.value
          case None => Result.clientErrorF(s"$service doesn't have a modelversion")
        }
      }
    }
  }

  private def findSignature(version: ModelVersion, signature: String) = {
    Future.successful {
      version.modelContract.signatures
        .find(_.signatureName == signature)
        .toHResult(Result.ClientError(s"Can't find signature $signature in $version"))
    }
  }

  private def createDetailedServiceDesc(service: ServiceCreationDescription, modelVersion: ModelVersion, runtime: Runtime, environment: Environment, signature: Option[ModelSignature]) = {
    Result.okF(
      DetailedServiceDescription(
        runtime,
        modelVersion,
        environment,
        service.weight,
        signature
      )
    )
  }

  private def inferAppContract(applicationName: String, graph: ApplicationExecutionGraph): HFResult[ModelContract] = {
    logger.debug(applicationName)
    graph.stages match {
      case stage :: Nil if stage.services.lengthCompare(1) == 0 => // single model version
        stage.services.headOption match {
          case Some(serviceDesc) =>
            Result.okF(serviceDesc.modelVersion.modelContract)
          case None => Result.clientErrorF(s"Can't infer contract for an empty stage")
        }

      case _ =>
        Result.okF(
          ModelContract(
            applicationName,
            Seq(inferPipelineSignature(applicationName, graph))
          )
        )
    }
  }

  private def inferStageSignature(serviceDescs: Seq[DetailedServiceDescription]): HResult[ModelSignature] = {
    val signatures = serviceDescs.map { service =>
      service.signature match {
        case Some(sig) => Result.ok(sig)
        case None => Result.clientError(s"$service doesn't have a signature")
      }
    }
    val errors = signatures.filter(_.isLeft).map(_.left.get)
    if (errors.nonEmpty) {
      Result.clientError(s"Errors while inferring stage signature: $errors")
    } else {
      val values = signatures.map(_.right.get)
      Result.ok(
        values.foldRight(ModelSignature.defaultInstance) {
          case (sig1, sig2) => ModelSignatureOps.merge(sig1, sig2)
        }
      )
    }
  }

  private def inferPipelineSignature(name: String, graph: ApplicationExecutionGraph): ModelSignature = {
    ModelSignature(
      name,
      graph.stages.head.signature.get.inputs,
      graph.stages.last.signature.get.outputs
    )
  }

  private def responseToJsObject(rr: PredictResponse): JsObject = {
    val fields = rr.outputs.mapValues(v => TensorJsonLens.toJson(TypedTensorFactory.create(v)))
    JsObject(fields)
  }
}