package com.gamblr.writer;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="gamblr.administration.worker-enabled",havingValue="true",matchIfMissing=true)
class DailyCreditWorker {
    private static final Logger log=LoggerFactory.getLogger(DailyCreditWorker.class);
    private final DailyCreditService service;
    private UUID after;
    DailyCreditWorker(DailyCreditService service) {this.service=service;}
    @Scheduled(cron="0 0 0 * * *",zone="America/Sao_Paulo")
    @Scheduled(fixedDelayString="${gamblr.daily.poll-ms:60000}",initialDelayString="${gamblr.daily.initial-ms:5000}")
    void tick() {
        try {
            var groups=service.batch(after);
            for(UUID group:groups){try{service.credit(group);}catch(RuntimeException error){log.warn("Crédito diário do grupo {} será repetido. Confira conexão e V009.",group);}after=group;}
            if(groups.size()<100)after=null;
        }catch(RuntimeException error){after=null;log.warn("Crédito diário indisponível. Confira conexão e migração V009.");}
    }
}
