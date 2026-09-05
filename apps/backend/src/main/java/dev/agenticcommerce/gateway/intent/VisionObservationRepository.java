package dev.agenticcommerce.gateway.intent;

import static dev.agenticcommerce.gateway.intent.VisualCommerceModels.*;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class VisionObservationRepository {
    private final JdbcClient jdbc;private final ObjectMapper mapper;
    public VisionObservationRepository(JdbcClient jdbc,ObjectMapper mapper){this.jdbc=jdbc;this.mapper=mapper;}
    public StoredVisionObservation create(UUID buyerId,UUID requestId,UUID threadId,UUID sourceMessageId,
            ValidatedImage image,VisionObservationProvider.Observed observed,String observationHash){return jdbc.sql("""
            INSERT INTO buyer_visual_observation(request_id,buyer_actor_id,thread_id,source_message_id,
              mime_type,original_filename,size_bytes,width_pixels,height_pixels,image_sha256,
              observation,observation_hash,provider_name,provider_model)
            VALUES(:request,:buyer,:thread,:message,:mime,:filename,:size,:width,:height,:imageHash,
              CAST(:observation AS jsonb),:observationHash,:provider,:model) RETURNING *
            """).param("request",requestId).param("buyer",buyerId).param("thread",threadId).param("message",sourceMessageId)
            .param("mime",image.mimeType()).param("filename",image.originalFilename()).param("size",image.sizeBytes())
            .param("width",image.width()).param("height",image.height()).param("imageHash",image.sha256())
            .param("observation",mapper.writeValueAsString(observed.observation())).param("observationHash",observationHash)
            .param("provider",observed.provider()).param("model",observed.model()).query(this::record).single();}
    public Optional<StoredVisionObservation> findByRequest(UUID buyerId,UUID requestId){return jdbc.sql("SELECT * FROM buyer_visual_observation WHERE buyer_actor_id=:buyer AND request_id=:request")
            .param("buyer",buyerId).param("request",requestId).query(this::record).optional();}
    public Optional<StoredVisionObservation> findByMessage(UUID buyerId,UUID threadId,UUID messageId){return jdbc.sql("SELECT * FROM buyer_visual_observation WHERE buyer_actor_id=:buyer AND thread_id=:thread AND source_message_id=:message")
            .param("buyer",buyerId).param("thread",threadId).param("message",messageId).query(this::record).optional();}
    private StoredVisionObservation record(ResultSet rs,int n)throws SQLException{return new StoredVisionObservation(
            rs.getObject("observation_id",UUID.class),rs.getObject("request_id",UUID.class),rs.getObject("thread_id",UUID.class),
            rs.getObject("source_message_id",UUID.class),rs.getString("mime_type"),rs.getString("original_filename"),rs.getLong("size_bytes"),
            rs.getInt("width_pixels"),rs.getInt("height_pixels"),rs.getString("image_sha256").strip(),
            mapper.readValue(rs.getString("observation"),VisionObservation.class),rs.getString("observation_hash").strip(),
            rs.getString("provider_name"),rs.getString("provider_model"));}
}
