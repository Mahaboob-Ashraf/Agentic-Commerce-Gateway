package dev.agenticcommerce.gateway.lifecycle;
import dev.agenticcommerce.gateway.agentization.service.CanonicalJsonService;import java.sql.Types;import java.time.*;import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;import org.springframework.stereotype.Service;import tools.jackson.databind.*;
@Service public class LifecycleAuditService {private final JdbcClient jdbc;private final CanonicalJsonService canonical;private final ObjectMapper mapper;
 public LifecycleAuditService(JdbcClient j,CanonicalJsonService c,ObjectMapper m){jdbc=j;canonical=c;mapper=m;}
 public void record(UUID buyer,UUID merchant,UUID thread,String type,String subject,JsonNode evidence){JsonNode safe=evidence==null?mapper.createObjectNode():evidence;
  jdbc.sql("""
   INSERT INTO lifecycle_audit_event(buyer_actor_id,merchant_id,thread_id,event_type,subject_reference,evidence,evidence_hash,created_at)
   VALUES(:buyer,:merchant,:thread,:type,:subject,CAST(:evidence AS jsonb),:hash,:now)
   """).param("buyer",buyer).param("merchant",merchant).param("thread",thread).param("type",type).param("subject",subject).param("evidence",mapper.writeValueAsString(safe)).param("hash",canonical.hash(safe)).param("now",Instant.now().atOffset(ZoneOffset.UTC),Types.TIMESTAMP_WITH_TIMEZONE).update();}
 public JsonNode reference(String kind,String id){return mapper.createObjectNode().put(kind,id);}}
