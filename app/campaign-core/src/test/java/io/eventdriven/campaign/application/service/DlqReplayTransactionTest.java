package io.eventdriven.campaign.application.service;

import io.eventdriven.campaign.config.BatchProperties;
import io.eventdriven.campaign.domain.entity.*;
import io.eventdriven.campaign.domain.repository.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes=DlqReplayTransactionTest.Config.class)
@EnabledIfEnvironmentVariable(named="DLQ_BATCH_MYSQL_URL",matches="jdbc:mysql://127.0.0.1:[0-9]+/dlq_batch_test.*")
class DlqReplayTransactionTest {
    @Configuration @EnableTransactionManagement @EnableJpaAuditing
    @Import(io.eventdriven.campaign.batch.DlqReplayJobConfig.class)
    @EnableJpaRepositories(basePackageClasses=DlqMessageRepository.class)
    static class Config {
        @Bean DataSource dataSource(){return new DriverManagerDataSource(System.getenv("DLQ_BATCH_MYSQL_URL"),"root","batch-local-only");}
        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource ds){
            org.flywaydb.core.Flyway.configure().dataSource(ds).load().migrate();
            var f=new LocalContainerEntityManagerFactoryBean();f.setDataSource(ds);
            f.setPackagesToScan("io.eventdriven.campaign.domain.entity");f.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            f.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto","validate"));return f;
        }
        @Bean JpaTransactionManager transactionManager(EntityManagerFactory f){return new JpaTransactionManager(f);}
        @Bean org.springframework.batch.core.repository.JobRepository jobRepository(DataSource ds, JpaTransactionManager tm) throws Exception {
            var f=new org.springframework.batch.core.repository.support.JobRepositoryFactoryBean();
            f.setDataSource(ds); f.setTransactionManager(tm); f.afterPropertiesSet(); return f.getObject();
        }
        @Bean org.springframework.batch.core.launch.JobLauncher jobLauncher(org.springframework.batch.core.repository.JobRepository repo) throws Exception {
            var l=new org.springframework.batch.core.launch.support.TaskExecutorJobLauncher();l.setJobRepository(repo);l.afterPropertiesSet();return l;
        }
        @Bean io.eventdriven.campaign.batch.DlqReplayJobExecutionListener listener(DlqReplayService s){return new io.eventdriven.campaign.batch.DlqReplayJobExecutionListener(s,mock(SlackNotificationService.class));}
        @Bean DlqReplayPolicyService policy(){return mock(DlqReplayPolicyService.class);}
        @Bean @SuppressWarnings("unchecked") KafkaTemplate<String,String> kafka(){return mock(KafkaTemplate.class);}
        @Bean DlqReplayService service(DlqMessageRepository m,DlqReplayExecutionRepository e,DlqReplayExecutionItemRepository i,DlqReplayPolicyService p,KafkaTemplate<String,String> k){return new DlqReplayService(m,e,i,p,k,new BatchProperties(),new SimpleMeterRegistry());}
    }
    @Autowired DlqReplayService service;
    @Autowired DlqMessageRepository messages;
    @Autowired DlqReplayExecutionRepository executions;
    @Autowired DlqReplayExecutionItemRepository items;
    @Autowired DlqReplayPolicyService policy;
    @Autowired KafkaTemplate<String,String> kafka;
    @Autowired DataSource dataSource;
    @Autowired JpaTransactionManager transactionManager;
    @Autowired org.springframework.batch.core.job.Job dlqReplayJob;
    @Autowired org.springframework.batch.core.launch.JobLauncher jobLauncher;
    @BeforeEach void resetDb(){new org.springframework.jdbc.core.JdbcTemplate(dataSource).execute("DELETE FROM participation_history");items.deleteAll();executions.deleteAll();messages.deleteAll();reset(kafka,policy);}
    DlqMessageRecord seed(){return messages.save(new DlqMessageRecord(DlqSourceType.CONSUMER,"topic","42","{}","INSERT_FAILED",null,1L,42L,100L,DlqReplayClassification.CONDITIONALLY_REPLAYABLE));}
    void decision(){when(policy.decide(any())).thenReturn(DlqReplayPolicyService.DlqReplayDecision.replay("42","sequence100","REPLAYABLE","test"));}
    @Test void checkpointSurvivesNewInvocationAndPreventsRepeat(){
        var m=seed();decision();when(kafka.send(anyString(),anyString(),anyString())).thenReturn(CompletableFuture.completedFuture(null));
        var e=service.createExecution("test",false,null,null,null,null,10);
        assertThat(service.processNext(e.getId())).isEqualTo(DlqReplayService.ProcessResult.CONTINUE);
        assertThat(service.processNext(e.getId())).isEqualTo(DlqReplayService.ProcessResult.DONE);
        assertThat(executions.findById(e.getId()).orElseThrow().getProcessedCount()).isEqualTo(1);
        assertThat(messages.findById(m.getId()).orElseThrow().getProcessingStatus()).isEqualTo(DlqMessageProcessingStatus.REPLAYED);
        verify(kafka,times(1)).send(anyString(),anyString(),anyString());
    }
    @Test void twoExecutionsDoNotPublishSameRowTwice() throws Exception {
        seed();decision();var entered=new CountDownLatch(1);var release=new CompletableFuture<org.springframework.kafka.support.SendResult<String,String>>();
        when(kafka.send(anyString(),anyString(),anyString())).thenAnswer(x->{entered.countDown();return release;});
        var a=service.createExecution("a",false,null,null,null,null,10);var b=service.createExecution("b",false,null,null,null,null,10);
        var pool=Executors.newFixedThreadPool(2);
        try {
            var first=pool.submit(()->service.processNext(a.getId()));assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();
            var second=pool.submit(()->service.processNext(b.getId()));release.complete(null);
            first.get(8,TimeUnit.SECONDS);second.get(8,TimeUnit.SECONDS);
            verify(kafka,times(1)).send(anyString(),anyString(),anyString());
        } finally {release.complete(null);pool.shutdownNow();}
    }
    @Test void publishCallExceptionLeavesRecoverableMessage(){
        var m=seed();decision();when(kafka.send(anyString(),anyString(),anyString())).thenThrow(new IllegalStateException("offline"));
        var e=service.createExecution("test",false,null,null,null,null,10);service.processNext(e.getId());
        assertThat(messages.findById(m.getId()).orElseThrow().getProcessingStatus()).isEqualTo(DlqMessageProcessingStatus.PENDING);
        assertThat(executions.findById(e.getId()).orElseThrow().getPublishFailedCount()).isEqualTo(1);
    }
    @Test void databaseRollbackAfterKafkaSuccessCanBeResumed() {
        var m=seed();decision();when(kafka.send(anyString(),anyString(),anyString())).thenReturn(CompletableFuture.completedFuture(null));
        var e=service.createExecution("test",false,null,null,null,null,10);
        var jdbc=new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        jdbc.execute("CREATE TRIGGER fail_replay_item BEFORE INSERT ON dlq_replay_execution_item FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected result failure'");
        try {assertThatThrownBy(()->service.processNext(e.getId())).isInstanceOf(RuntimeException.class);}
        finally {jdbc.execute("DROP TRIGGER fail_replay_item");}
        assertThat(executions.findById(e.getId()).orElseThrow().getLastMessageId()).isZero();
        assertThat(messages.findById(m.getId()).orElseThrow().getProcessingStatus()).isEqualTo(DlqMessageProcessingStatus.PENDING);
        service.processNext(e.getId());
        verify(kafka,times(2)).send(anyString(),anyString(),anyString());
        assertThat(executions.findById(e.getId()).orElseThrow().getProcessedCount()).isEqualTo(1);
    }
    @Test void sameKafkaSourceIsPersistedOnlyOnce() {
        when(kafka.send(anyString(),anyString(),anyString())).thenReturn(CompletableFuture.completedFuture(null));
        var dlq=new DlqMessageService(messages,kafka,tools.jackson.databind.json.JsonMapper.builder().build(),new SimpleMeterRegistry());
        for(int n=0;n<2;n++) {
            var p=dlq.createConsumerPayload("42","{}","INVALID_PAYLOAD",null);
            p.setOriginalPartition(1);p.setOriginalOffset(88L);dlq.publishAndStore(p);
        }
        assertThat(messages.count()).isEqualTo(1);
    }

    @Test void verificationSeparatesPublishedFromDatabaseConfirmed() {
        seed();decision();when(kafka.send(anyString(),anyString(),anyString())).thenReturn(CompletableFuture.completedFuture(null));
        var e=service.createExecution("test",false,null,null,null,null,10);service.processNext(e.getId());
        var jdbc=new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        var verification=new DlqReplayVerificationService(jdbc);
        var before=(Map<?,?>)verification.verify(e.getId(),0,100).get("summary");
        assertThat(((Number)before.get("published")).longValue()).isEqualTo(1);
        assertThat(((Number)before.get("dbConfirmed")).longValue()).isZero();
        jdbc.execute("INSERT INTO campaign (id,name,total_stock,current_stock,status,created_at,updated_at) VALUES (1,'test',100,100,'OPEN',NOW(),NOW()) ON DUPLICATE KEY UPDATE id=id");
        jdbc.execute("INSERT INTO participation_history (campaign_id,user_id,sequence,status,created_at,updated_at) VALUES (1,42,100,'SUCCESS',NOW(),NOW()) ON DUPLICATE KEY UPDATE sequence=100");
        var after=(Map<?,?>)verification.verify(e.getId(),0,100).get("summary");
        assertThat(((Number)after.get("dbConfirmed")).longValue()).isEqualTo(1);
    }

    @Test void realSpringBatchJobPersistsThreeStepExecutions() throws Exception {
        seed();decision();when(kafka.send(anyString(),anyString(),anyString())).thenReturn(CompletableFuture.completedFuture(null));
        var e=service.createExecution("job-test",false,null,null,null,null,10);
        var params=new org.springframework.batch.core.job.parameters.JobParametersBuilder()
            .addLong("replayExecutionId",e.getId()).addString("testRun",UUID.randomUUID().toString()).toJobParameters();
        var job=jobLauncher.run(dlqReplayJob,params);
        assertThat(job.getStatus()).isEqualTo(org.springframework.batch.core.BatchStatus.COMPLETED);
        var jdbc=new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM BATCH_STEP_EXECUTION WHERE JOB_EXECUTION_ID=? AND STATUS='COMPLETED'",Long.class,job.getId())).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COMMIT_COUNT FROM BATCH_STEP_EXECUTION WHERE JOB_EXECUTION_ID=? AND STEP_NAME='dlqReplayProcessStep'",Long.class,job.getId())).isGreaterThanOrEqualTo(2);
        assertThat(executions.findById(e.getId()).orElseThrow().getStatus()).isEqualTo(DlqReplayExecutionStatus.COMPLETED);
    }

    @Test void failedJobCanResumeSameItemAfterRepeatedBrokerOutage() throws Exception {
        var message=seed();decision();
        when(kafka.send(anyString(),anyString(),anyString())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker offline")));
        var execution=service.createExecution("outage-test",false,null,null,null,null,10);
        for(int attempt=0;attempt<3;attempt++) {
            var params=new org.springframework.batch.core.job.parameters.JobParametersBuilder()
                .addLong("replayExecutionId",execution.getId()).addString("attempt",UUID.randomUUID().toString()).toJobParameters();
            var job=jobLauncher.run(dlqReplayJob,params);
            assertThat(job.getStatus()).isEqualTo(org.springframework.batch.core.BatchStatus.FAILED);
            var saved=executions.findById(execution.getId()).orElseThrow();
            assertThat(saved.getLastMessageId()).isZero();
            assertThat(saved.getProcessedCount()).isZero();
            assertThat(saved.getPublishFailedCount()).isEqualTo(attempt+1);
            assertThat(messages.findById(message.getId()).orElseThrow().getProcessingStatus()).isEqualTo(DlqMessageProcessingStatus.PENDING);
        }
        assertThat(items.count()).isEqualTo(1); // Same item outcome is updated, not inserted again.
        when(kafka.send(anyString(),anyString(),anyString())).thenReturn(CompletableFuture.completedFuture(null));
        var params=new org.springframework.batch.core.job.parameters.JobParametersBuilder()
            .addLong("replayExecutionId",execution.getId()).addString("attempt",UUID.randomUUID().toString()).toJobParameters();
        assertThat(jobLauncher.run(dlqReplayJob,params).getStatus()).isEqualTo(org.springframework.batch.core.BatchStatus.COMPLETED);
        assertThat(executions.findById(execution.getId()).orElseThrow().getProcessedCount()).isEqualTo(1);
        assertThat(items.count()).isEqualTo(1);
        assertThat(messages.findById(message.getId()).orElseThrow().getProcessingStatus()).isEqualTo(DlqMessageProcessingStatus.REPLAYED);
    }

    @Test void resourcelessJobRepositoryAlsoFailsAndResumesUsingBusinessCursor() throws Exception {
        seed(); decision();
        var repository=new org.springframework.batch.core.repository.support.ResourcelessJobRepository();
        var manager=transactionManager;
        var config=new io.eventdriven.campaign.batch.DlqReplayJobConfig(repository,manager,service,
                new io.eventdriven.campaign.batch.DlqReplayJobExecutionListener(service,mock(SlackNotificationService.class)));
        var job=config.dlqReplayJob(config.dlqReplayStartStep(),config.dlqReplayProcessStep(),config.dlqReplayFinishStep());
        var launcher=new org.springframework.batch.core.launch.support.TaskExecutorJobLauncher();
        launcher.setJobRepository(repository);launcher.afterPropertiesSet();
        var execution=service.createExecution("memory-repository",false,null,null,null,null,10);
        when(kafka.send(anyString(),anyString(),anyString())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("offline")));
        var first=new org.springframework.batch.core.job.parameters.JobParametersBuilder().addLong("replayExecutionId",execution.getId()).addString("attempt","first").toJobParameters();
        assertThat(launcher.run(job,first).getStatus()).isEqualTo(org.springframework.batch.core.BatchStatus.FAILED);
        assertThat(service.getExecution(execution.getId()).getLastMessageId()).isZero();
        when(kafka.send(anyString(),anyString(),anyString())).thenReturn(CompletableFuture.completedFuture(null));
        var second=new org.springframework.batch.core.job.parameters.JobParametersBuilder().addLong("replayExecutionId",execution.getId()).addString("attempt","second").toJobParameters();
        assertThat(launcher.run(job,second).getStatus()).isEqualTo(org.springframework.batch.core.BatchStatus.COMPLETED);
        assertThat(service.getExecution(execution.getId()).getProcessedCount()).isEqualTo(1);
    }

    @Test void unavailableBrokerAttemptsThenLiveBrokerPreservesAllFiveParticipants() throws Exception {
        int port;
        try (var socket=new java.net.ServerSocket(0)) { port=socket.getLocalPort(); }
        String topic=io.eventdriven.campaign.config.KafkaConfig.TOPIC_NAME;
        var broker=new org.springframework.kafka.test.EmbeddedKafkaKraftBroker(1,1,topic);
        broker.brokerListProperty("pr113.test.kafka.brokers");
        var settings=new HashMap<String,Object>();
        settings.put("bootstrap.servers","127.0.0.1:"+port);
        settings.put("key.serializer",org.apache.kafka.common.serialization.StringSerializer.class);
        settings.put("value.serializer",org.apache.kafka.common.serialization.StringSerializer.class);
        settings.put("acks","all");settings.put("enable.idempotence",true);
        var original=new org.springframework.kafka.core.DefaultKafkaProducerFactory<String,String>(settings);
        var factory=new io.eventdriven.campaign.config.DlqReplayKafkaConfig().dlqReplayProducerFactory(original);
        var actualKafka=new org.springframework.kafka.core.KafkaTemplate<String,String>(factory);
        Object target=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(service);
        var mapper=tools.jackson.databind.json.JsonMapper.builder().build();
        var jdbc=new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        jdbc.execute("INSERT INTO campaign (id,name,total_stock,current_stock,status,created_at,updated_at) VALUES (1,'test',100,100,'OPEN',NOW(),NOW()) ON DUPLICATE KEY UPDATE id=id");
        for(long n=0;n<5;n++) messages.save(new DlqMessageRecord(DlqSourceType.CONSUMER,topic,""+(42+n),"{}","INSERT_FAILED",null,1L,42+n,100+n,DlqReplayClassification.CONDITIONALLY_REPLAYABLE));
        when(policy.decide(any())).thenAnswer(inv->{
            DlqMessageRecord m=inv.getArgument(0);
            return DlqReplayPolicyService.DlqReplayDecision.replay(""+m.getUserId(),mapper.writeValueAsString(new io.eventdriven.campaign.application.event.ParticipationEvent(m.getCampaignId(),m.getUserId(),m.getSequence())),"REPLAYABLE","test");
        });
        var execution=service.createExecution("real-kafka",false,null,null,null,null,10);
        org.springframework.test.util.ReflectionTestUtils.setField(target,"kafkaTemplate",actualKafka);
        boolean started=false;
        try {
            for(int n=0;n<3;n++) {
                var params=new org.springframework.batch.core.job.parameters.JobParametersBuilder().addLong("replayExecutionId",execution.getId()).addString("attempt",UUID.randomUUID().toString()).toJobParameters();
                long begin=System.nanoTime();
                assertThat(jobLauncher.run(dlqReplayJob,params).getStatus()).isEqualTo(org.springframework.batch.core.BatchStatus.FAILED);
                long elapsedMs=java.time.Duration.ofNanos(System.nanoTime()-begin).toMillis();
                System.out.println("Replay unavailable-broker attempt="+(n+1)+", elapsedMs="+elapsedMs);
                assertThat(elapsedMs).isLessThan(10_000);
                assertThat(messages.findAll()).allMatch(m->m.getProcessingStatus()==DlqMessageProcessingStatus.PENDING);
                assertThat(executions.findById(execution.getId()).orElseThrow().getLastMessageId()).isZero();
            }
            broker.afterPropertiesSet();started=true;
            // KRaft test-kit chooses its own port; use its published address for recovery.
            factory.reset();original.destroy();
            settings.put("bootstrap.servers",broker.getBrokersAsString());
            original=new org.springframework.kafka.core.DefaultKafkaProducerFactory<String,String>(settings);
            factory=new io.eventdriven.campaign.config.DlqReplayKafkaConfig().dlqReplayProducerFactory(original);
            actualKafka=new org.springframework.kafka.core.KafkaTemplate<String,String>(factory);
            org.springframework.test.util.ReflectionTestUtils.setField(target,"kafkaTemplate",actualKafka);
            var params=new org.springframework.batch.core.job.parameters.JobParametersBuilder().addLong("replayExecutionId",execution.getId()).addString("attempt",UUID.randomUUID().toString()).toJobParameters();
            assertThat(jobLauncher.run(dlqReplayJob,params).getStatus()).isEqualTo(org.springframework.batch.core.BatchStatus.COMPLETED);
            var consumerSettings=new HashMap<String,Object>();
            consumerSettings.put("bootstrap.servers",broker.getBrokersAsString());
            consumerSettings.put("group.id",UUID.randomUUID().toString());consumerSettings.put("auto.offset.reset","earliest");consumerSettings.put("enable.auto.commit",false);
            consumerSettings.put("key.deserializer",org.apache.kafka.common.serialization.StringDeserializer.class);
            consumerSettings.put("value.deserializer",org.apache.kafka.common.serialization.StringDeserializer.class);
            var records=new ArrayList<org.apache.kafka.clients.consumer.ConsumerRecord<String,String>>();
            try (var reader=new org.apache.kafka.clients.consumer.KafkaConsumer<String,String>(consumerSettings)) {
                reader.subscribe(List.of(topic));long deadline=System.nanoTime()+java.time.Duration.ofSeconds(15).toNanos();
                while(records.size()<5 && System.nanoTime()<deadline) reader.poll(java.time.Duration.ofMillis(200)).forEach(records::add);
            }
            assertThat(records).hasSize(5);
            var ack=mock(org.springframework.kafka.support.Acknowledgment.class);
            var consumer=new io.eventdriven.campaign.application.consumer.ParticipationEventConsumer(mapper,jdbc,mock(SlackNotificationService.class),new DlqMessageService(messages,actualKafka,mapper,new SimpleMeterRegistry()),new SimpleMeterRegistry());
            consumer.consumeParticipationEvent(records,ack);verify(ack).acknowledge();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM participation_history WHERE campaign_id=1 AND user_id BETWEEN 42 AND 46 AND sequence=user_id+58 AND status='SUCCESS'",Long.class)).isEqualTo(5);
            var summary=(Map<?,?>)new DlqReplayVerificationService(jdbc).verify(execution.getId(),0,100).get("summary");
            assertThat(((Number)summary.get("published")).longValue()).isEqualTo(5);
            assertThat(((Number)summary.get("dbConfirmed")).longValue()).isEqualTo(5);
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(target,"kafkaTemplate",kafka);
            factory.reset();original.destroy();if(started) broker.destroy();
        }
    }

}
