package pl.gov.mc.protegosafe.domain.usecase.covidtest

import io.reactivex.Completable
import io.reactivex.Single
import io.reactivex.schedulers.Schedulers
import pl.gov.mc.protegosafe.domain.exception.NoInternetConnectionException
import pl.gov.mc.protegosafe.domain.executor.PostExecutionThread
import pl.gov.mc.protegosafe.domain.manager.InternetConnectionManager
import pl.gov.mc.protegosafe.domain.model.OutgoingBridgeDataResultComposer
import pl.gov.mc.protegosafe.domain.model.OutgoingBridgePayloadMapper
import pl.gov.mc.protegosafe.domain.model.PinItem
import pl.gov.mc.protegosafe.domain.model.ResultStatus
import pl.gov.mc.protegosafe.domain.model.TestSubscriptionItem
import pl.gov.mc.protegosafe.domain.repository.CovidTestRepository
import java.net.UnknownHostException
import java.util.UUID

class UploadTestSubscriptionPinUseCase(
    private val covidTestRepository: CovidTestRepository,
    private val payloadMapper: OutgoingBridgePayloadMapper,
    private val internetConnectionManager: InternetConnectionManager,
    private val resultComposer: OutgoingBridgeDataResultComposer,
    private val postExecutionThread: PostExecutionThread
) {
    fun execute(payload: String): Single<String> {
        return checkInternet()
            .flatMap {
                if (it.isConnected()) {
                    parsePayload(payload)
                        .flatMap { pinItem ->
                            startUpload(pinItem.pin)
                        }
                } else {
                    throw NoInternetConnectionException()
                }
            }
            .subscribeOn(Schedulers.io())
            .observeOn(postExecutionThread.scheduler)
    }

    private fun startUpload(pin: String): Single<String> {
        return covidTestRepository.getTestSubscription(pin, UUID.randomUUID().toString())
            .flatMapCompletable { testSubscription ->
                saveData(pin, testSubscription)
            }
            .andThen(
                Single.fromCallable {
                    resultComposer.composeUploadTestPinResult(ResultStatus.SUCCESS)
                }
            )
            .onErrorResumeNext {
                Single.fromCallable {
                    if (it is UnknownHostException) {
                        throw it
                    } else {
                        resultComposer.composeUploadTestPinResult(ResultStatus.FAILURE)
                    }
                }
            }
    }

    private fun saveData(pin: String, testSubscription: TestSubscriptionItem): Completable {
        return covidTestRepository.saveTestSubscription(testSubscription)
            .andThen(covidTestRepository.saveTestSubscriptionPin(pin))
    }

    private fun checkInternet(): Single<InternetConnectionManager.InternetConnectionStatus> {
        return Single.fromCallable {
            internetConnectionManager.getInternetConnectionStatus()
        }
    }

    private fun parsePayload(payload: String): Single<PinItem> {
        return Single.fromCallable {
            payloadMapper.toPinItem(payload)
        }
    }
}