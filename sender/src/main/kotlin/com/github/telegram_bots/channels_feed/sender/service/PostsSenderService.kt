package com.github.telegram_bots.channels_feed.sender.service

import com.github.telegram_bots.channels_feed.sender.config.properties.SenderProperties
import com.github.telegram_bots.channels_feed.sender.domain.*
import com.github.telegram_bots.channels_feed.sender.domain.ProcessedPost.Mode.AS_IS
import com.github.telegram_bots.channels_feed.sender.domain.ProcessedPostGroup.Type
import com.github.telegram_bots.channels_feed.sender.domain.ProcessedPostGroup.Type.FULL
import com.github.telegram_bots.channels_feed.sender.util.RetryWithDelay
import com.pengrad.telegrambot.TelegramBot
import com.pengrad.telegrambot.model.request.InlineKeyboardButton
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup
import com.pengrad.telegrambot.model.request.ParseMode
import com.pengrad.telegrambot.request.BaseRequest
import com.pengrad.telegrambot.request.ForwardMessage
import com.pengrad.telegrambot.request.SendMessage
import com.pengrad.telegrambot.response.BaseResponse
import io.reactivex.Flowable
import io.reactivex.Single
import io.reactivex.rxkotlin.zipWith
import io.reactivex.schedulers.Schedulers
import mu.KLogging
import org.reactivestreams.Subscription
import org.springframework.cloud.stream.annotation.EnableBinding
import org.springframework.cloud.stream.annotation.StreamListener
import org.springframework.cloud.stream.messaging.Sink
import org.springframework.stereotype.Service
import java.util.concurrent.TimeUnit.*;
import javax.annotation.PreDestroy

@Service
@EnableBinding(Sink::class)
class PostsSenderService(
        private val props: SenderProperties,
        private val bot: TelegramBot,
        private val notifications: NotificationsService
) {
    companion object : KLogging()

    private var subscription: Subscription? = null

    @PreDestroy
    fun onDestroy() = subscription?.cancel()

    @StreamListener(Sink.INPUT)
    fun subscribe(group: ProcessedPostGroup) {
        enableExceptionPropagate()

        Single.just(group)
                .delay(props.rateLimit, props.rateLimitUnit)
                .flatMapPublisher { getNotNotified(it) }
                .flatMapSingle { preparePost(it) }
                .flatMapSingle { sendPost(it) }
                .doOnSubscribe { subscription = it }
                .blockingSubscribe(
                        this::markSubscription,
                        this::onError,
                        { markChannel(group) }
                )
    }

    private fun getNotNotified(group: ProcessedPostGroup): Flowable<PostData> {
        return notifications
                .listNotNotified(group.channelId, group.postId)
                .zipWith(Flowable.just(group).repeat())
                .doOnSubscribe { logger.info { "[PROCESSING] $group" } }
    }

    private fun preparePost(data: PostData): Single<RequestData> {
        fun User.getChatId(): Any = redirectUrl?.let { "@$it" } ?: telegramId
        fun ProcessedPostGroup.getPost(type: Type): ProcessedPost {
            return posts.getOrElse(type, { throw IllegalStateException("Impossible to happen") })
        }
        fun ProcessedPost.buildRequest(chatId: Any, group: ProcessedPostGroup): BaseRequest<*, *> {
            return when (mode) {
                AS_IS -> ForwardMessage(chatId, "@${group.channelUrl}", group.postId)
                else -> SendMessage(chatId, text)
                        .disableWebPagePreview(!previewEnabled)
                        .parseMode(ParseMode.valueOf(mode.name))
                        .replyMarkup(
                                InlineKeyboardMarkup(arrayOf(
                                        InlineKeyboardButton("Mark as read")
                                                .callbackData("mark")
                                ))
                        )
            }
        }

        return Single.just(data)
                .map { (user, group) ->
                    val post = group.getPost(FULL)
                    val chatId = user.getChatId()
                    val request = post.buildRequest(chatId, group)

                    (user to group) to request
                }
    }

    private fun sendPost(data: RequestData): Single<PostData> {
        fun BaseResponse.checkStatus(): BaseResponse {
            return when {
                isOk -> this
                errorCode() == 403 -> this
                errorCode() == 400 && description() == "Bad Request: message to forward not found" -> this
                else -> throw TelegramException(errorCode(), description())
            }
        }
        val handler = object : RetryWithDelay.Handler {
            override fun accepts(throwable: Throwable) = throwable is TelegramException && throwable.code == 429
            override fun handle(throwable: Throwable) = Flowable.timer((throwable as TelegramException).value, SECONDS)
        }

        val (info, request) = data

        return Single.just(request)
                .map { bot.execute(it) }
                .map(BaseResponse::checkStatus)
                .doOnError { logger.warn { "[SEND ERROR] ${it.message}" } }
                .retryWhen(RetryWithDelay(
                        tries = 10,
                        delay = 5 to SECONDS,
                        backOff = 2.0,
                        maxDelay = 30 to SECONDS,
                        handler = handler
                ))
                .zipWith(Single.just(info), { _, i -> i })
    }

    private fun markSubscription(data: PostData) {
        val (user, group) = data

        notifications.markSubscription(user.id, group.channelId, group.postId)
                .subscribeOn(Schedulers.io())
                .doOnComplete { logger.info { "[MARK_SUB] ${user.id}:${group.channelId} to ${group.postId}" } }
                .subscribe()
    }

    private fun markChannel(group: ProcessedPostGroup) {
        notifications.markChannel(group.channelId, group.postId)
                .subscribeOn(Schedulers.io())
                .doOnComplete { logger.info { "[MARK_CHANNEL] ${group.channelId} to ${group.postId}" } }
                .subscribe()
    }

    private fun onError(throwable: Throwable) {
        logger.error("[PROCESSING ERROR]", throwable)
        throw throwable
    }

    private fun enableExceptionPropagate() = Thread.currentThread().setUncaughtExceptionHandler { _, e -> throw e }

    private class TelegramException(val code: Int, val description: String)
        : RuntimeException("[$code] $description", null, false, false) {
        val value: Long = description.split(" ").lastOrNull()?.toLongOrNull() ?: 0
    }
}
