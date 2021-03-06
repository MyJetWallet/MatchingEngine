package com.lykke.matching.engine.config.spring

import com.google.common.util.concurrent.ThreadFactoryBuilder
import com.lykke.matching.engine.utils.config.Config
import com.lykke.utils.logging.ThrottlingLogger
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.core.env.get
import org.springframework.core.task.TaskExecutor
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.SchedulingConfigurer
import org.springframework.scheduling.concurrent.ConcurrentTaskExecutor
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.scheduling.config.ScheduledTaskRegistrar
import java.util.concurrent.*


@Configuration
@EnableScheduling
@EnableAsync
class ThreadPoolsConfig : SchedulingConfigurer {

    private companion object {
        private val LOGGER = ThrottlingLogger.getLogger("ThreadsHandler")
    }

    @Autowired
    private lateinit var config: Config

    @Autowired
    private lateinit var environment: Environment

    override fun configureTasks(taskRegistrar: ScheduledTaskRegistrar) {
        taskRegistrar.setTaskScheduler(taskScheduler())
    }

    @Bean
    fun taskScheduler(): TaskScheduler {
        val threadPoolTaskScheduler = ThreadPoolTaskScheduler()
        threadPoolTaskScheduler.threadNamePrefix = "scheduled-task-"
        threadPoolTaskScheduler.poolSize = environment["concurrent.scheduler.pool.size"].toInt()
        return threadPoolTaskScheduler
    }

    @Suppress("SpringElInspection")
    @Bean
    fun clientRequestThreadPool(
        @Value("\${concurrent.client.request.pool.core.pool.size}") corePoolSize: Int,
        @Value("#{Config.matchingEngine.socket.maxConnections}") maxPoolSize: Int
    ): ThreadPoolTaskExecutor {
        val threadPoolTaskExecutor = ThreadPoolTaskExecutor()
        threadPoolTaskExecutor.threadNamePrefix = "client-connection-"
        threadPoolTaskExecutor.setQueueCapacity(0)
        threadPoolTaskExecutor.corePoolSize = corePoolSize
        threadPoolTaskExecutor.maxPoolSize = maxPoolSize

        return threadPoolTaskExecutor
    }

    @Bean
    fun rabbitPublishersThreadPool(): TaskExecutor {
        val logExceptionThreadPoolExecutor = ThreadPoolExecutorWithLogExceptionSupport(
            0, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS,
            SynchronousQueue<Runnable>(), "rabbit-publisher-%d"
        )
        return ConcurrentTaskExecutor(logExceptionThreadPoolExecutor)
    }

    @Suppress("SpringElInspection")
    @Bean
    fun orderBookSubscribersThreadPool(
        @Value("\${concurrent.orderbook.subscribers.pool.core.pool.size}") corePoolSize: Int,
        @Value("#{Config.matchingEngine.serverOrderBookMaxConnections}") maxPoolSize: Int?
    ): ThreadPoolTaskExecutor? {
        if (config.matchingEngine.serverOrderBookPort == null) {
            return null
        }

        val threadPoolTaskExecutor = ThreadPoolTaskExecutor()
        threadPoolTaskExecutor.threadNamePrefix = "orderbook-subscriber-connection-"
        threadPoolTaskExecutor.setQueueCapacity(0)
        threadPoolTaskExecutor.corePoolSize = corePoolSize
        threadPoolTaskExecutor.maxPoolSize = maxPoolSize!!
        return threadPoolTaskExecutor
    }

    private class ThreadPoolExecutorWithLogExceptionSupport(
        corePoolSize: Int,
        maxPoolSize: Int,
        keepAliveTime: Long,
        unit: TimeUnit,
        workQueue: BlockingQueue<Runnable>,
        defaultThreadNameFormat: String
    ) : ThreadPoolExecutor(
        corePoolSize, maxPoolSize,
        keepAliveTime,
        unit,
        workQueue,
        ThreadFactoryBuilder().setNameFormat(defaultThreadNameFormat).build()
    ) {

        override fun afterExecute(r: Runnable?, t: Throwable?) {
            super.afterExecute(r, t)
            var exception = t
            if (t == null && r is Future<*>) {
                try {
                    (r as Future<*>).get()
                } catch (ce: CancellationException) {
                    exception = ce
                } catch (ee: ExecutionException) {
                    exception = ee.cause
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt() // ignore/reset
                }
            }

            if (exception != null) {
                val message = "Unhandled exception occurred in thread: ${Thread.currentThread().name}"
                LOGGER.error(message, exception)
            }
        }
    }
}