package com.mesosphere.dcos.cassandra.scheduler;

import com.mesosphere.dcos.cassandra.common.tasks.CassandraTask;
import com.mesosphere.dcos.cassandra.scheduler.offer.CassandraOfferRequirementProvider;
import com.mesosphere.dcos.cassandra.scheduler.persistence.PersistenceException;
import com.mesosphere.dcos.cassandra.scheduler.tasks.CassandraTasks;
import org.apache.mesos.Protos;
import org.apache.mesos.SchedulerDriver;
import org.apache.mesos.offer.OfferAccepter;
import org.apache.mesos.offer.OfferEvaluator;
import org.apache.mesos.offer.OfferRecommendation;
import org.apache.mesos.offer.OfferRequirement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

public class CassandraRepairScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(
            CassandraRepairScheduler.class);

    private final OfferAccepter offerAccepter;
    private final CassandraOfferRequirementProvider offerRequirementProvider;
    private final CassandraTasks cassandraTasks;
    private final Random random = new Random();

    public CassandraRepairScheduler(
            CassandraOfferRequirementProvider requirementProvider,
            OfferAccepter offerAccepter, CassandraTasks cassandraTasks) {
        this.offerAccepter = offerAccepter;
        this.cassandraTasks = cassandraTasks;
        this.offerRequirementProvider = requirementProvider;
    }

    public List<Protos.OfferID> resourceOffers(final SchedulerDriver driver,
                                               final List<Protos.Offer> offers,
                                               final Set<String> ignore) {
        List<Protos.OfferID> acceptedOffers = Collections.emptyList();
        Optional<CassandraTask> terminatedOption = getTerminatedTask(ignore);

        if (terminatedOption.isPresent()) {
            try {
                CassandraTask terminated = terminatedOption.get();
                terminated = cassandraTasks.replaceTask(terminated);
                OfferRequirement offerReq =
                        offerRequirementProvider.getReplacementOfferRequirement(
                                terminated.toProto());
                OfferEvaluator offerEvaluator = new OfferEvaluator(offerReq);
                List<OfferRecommendation> recommendations =
                        offerEvaluator.evaluate(offers);
                LOGGER.debug("Got recommendations: {} for terminated task: {}",
                        recommendations,
                        terminated.getId());
                acceptedOffers = offerAccepter.accept(driver, recommendations);
            } catch (PersistenceException ex) {
                LOGGER.error(
                        String.format("Persistence error recovering " +
                                "terminated task %s", terminatedOption),
                        ex);
            }
        }
        return acceptedOffers;
    }

    private Optional<CassandraTask> getTerminatedTask(
            final Set<String> ignore) {

        List<CassandraTask> terminated =
                cassandraTasks.getTerminatedTasks().stream()
                        .filter(task -> !ignore.contains(task.getName()))
                        .collect(Collectors.toList());
        LOGGER.info("Terminated tasks size: {}", terminated.size());
        if (terminated.size() > 0) {
            return Optional.of(terminated.get(
                    random.nextInt(terminated.size())));
        } else {
            return Optional.empty();
        }
    }

}
