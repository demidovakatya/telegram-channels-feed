package com.github.telegram_bots.channels_feed.service

import com.github.telegram_bots.channels_feed.domain.RawPostData
import com.github.telegram_bots.channels_feed.domain.ProcessedPostGroup
import com.github.telegram_bots.channels_feed.domain.RawPost
import com.github.telegram_bots.channels_feed.domain.RawPost.PhotoContent
import com.github.telegram_bots.channels_feed.service.processor.PostProcessor
import io.reactivex.Observable
import io.reactivex.disposables.Disposable
import mu.KotlinLogging
import org.springframework.cloud.stream.annotation.EnableBinding
import org.springframework.cloud.stream.annotation.StreamListener
import org.springframework.cloud.stream.messaging.Processor
import org.springframework.messaging.support.MessageBuilder
import org.springframework.stereotype.Service
import javax.annotation.PreDestroy

@Service
@EnableBinding(Processor::class)
class PostProcessorService(
        private val processor: Processor,
        private val channelRepository: ChannelRepository,
        private val cachingService: TelegramCachingService,
        private val postProcessors: Collection<PostProcessor>
) {
    private val log = KotlinLogging.logger {}
    private var disposable: Disposable? = null

    @PreDestroy
    fun onDestroy() {
        disposable?.dispose()
    }

    @StreamListener(Processor.INPUT)
    fun process(raw: RawPost) {
        Observable.just(raw)
                .map(this::postData)
                .doOnNext { log.debug { "Raw post data: $it" } }
                .map(this::processPostData)
                .doOnNext { log.debug { "Processed post: $it" } }
                .doOnSubscribe { d -> disposable = d }
                .subscribe(this::send, this::onError)
    }

    private fun postData(raw: RawPost): RawPostData {
        val channel = channelRepository.find(raw.channelId)
        val fileId = when (raw.content) {
            is PhotoContent -> cachingService.compute(raw.content.photoId)
            else -> null
        }

        return RawPostData(raw, channel, fileId)
    }

    private fun processPostData(info: RawPostData): ProcessedPostGroup {
        val posts = postProcessors
                .map { p -> p.type to p.process(info) }
                .toMap()

        return ProcessedPostGroup(info.channel.id, info.raw.messageId, posts)
    }

    private fun send(postGroup: ProcessedPostGroup) {
        processor.output().send(MessageBuilder.withPayload(postGroup).build())
    }

    private fun onError(throwable: Throwable) = log.error("Send to queue error", throwable)
}
