package dev.agenticcommerce.gateway.intent;

import static dev.agenticcommerce.gateway.intent.CommerceRequestModels.*;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Repository
public class CommerceRequestRepository {
    private final JdbcClient jdbc;private final ObjectMapper mapper;
    public CommerceRequestRepository(JdbcClient jdbc,ObjectMapper mapper){this.jdbc=jdbc;this.mapper=mapper;}

    public Optional<CommerceRequestRecord> create(UUID buyerId,UUID requestId,UUID requestedThreadId,String text,String materialHash){return jdbc.sql("""
            INSERT INTO buyer_commerce_request(request_id,buyer_actor_id,requested_thread_id,normalized_text,material_hash)
            VALUES(:request,:buyer,:requestedThread,:text,:hash)
            ON CONFLICT (buyer_actor_id,request_id) DO NOTHING RETURNING *
            """).param("request",requestId).param("buyer",buyerId).param("requestedThread",requestedThreadId)
            .param("text",text).param("hash",materialHash).query(this::record).optional();}

    public Optional<CommerceRequestRecord> find(UUID buyerId,UUID requestId){return jdbc.sql("SELECT * FROM buyer_commerce_request WHERE buyer_actor_id=:buyer AND request_id=:request")
            .param("buyer",buyerId).param("request",requestId).query(this::record).optional();}

    public Optional<CommerceRequestRecord> latestForThread(UUID buyerId,UUID threadId){return jdbc.sql("""
            SELECT * FROM buyer_commerce_request
            WHERE buyer_actor_id=:buyer AND thread_id=:thread
            ORDER BY created_at DESC,commerce_request_id DESC LIMIT 1
            """).param("buyer",buyerId).param("thread",threadId).query(this::record).optional();}

    public Optional<CommerceRequestRecord> findForUpdate(UUID buyerId,UUID requestId){return jdbc.sql("SELECT * FROM buyer_commerce_request WHERE buyer_actor_id=:buyer AND request_id=:request FOR UPDATE")
            .param("buyer",buyerId).param("request",requestId).query(this::record).optional();}

    public CommerceRequestRecord attachThread(UUID buyerId,UUID requestId,UUID threadId){return jdbc.sql("""
            UPDATE buyer_commerce_request SET thread_id=:thread,updated_at=CURRENT_TIMESTAMP
            WHERE buyer_actor_id=:buyer AND request_id=:request AND request_status='RUNNING' RETURNING *
            """).param("thread",threadId).param("buyer",buyerId).param("request",requestId).query(this::record).single();}

    public CommerceRequestRecord complete(UUID buyerId,UUID requestId,RequestStatus status,CommerceRequestResult result,String failureCode){
        if(status==RequestStatus.RUNNING)throw new IllegalArgumentException("A completed request cannot remain RUNNING");
        return jdbc.sql("""
                UPDATE buyer_commerce_request SET request_status=:status,authoritative_result=CAST(:result AS jsonb),
                  failure_code=:failure,updated_at=CURRENT_TIMESTAMP,completed_at=CURRENT_TIMESTAMP
                WHERE buyer_actor_id=:buyer AND request_id=:request AND request_status='RUNNING' RETURNING *
                """).param("status",status.name()).param("result",mapper.writeValueAsString(result)).param("failure",failureCode,Types.VARCHAR)
                .param("buyer",buyerId).param("request",requestId).query(this::record).single();}

    private CommerceRequestRecord record(ResultSet rs,int n)throws SQLException{return new CommerceRequestRecord(
            rs.getObject("commerce_request_id",UUID.class),rs.getObject("request_id",UUID.class),rs.getObject("buyer_actor_id",UUID.class),
            rs.getObject("requested_thread_id",UUID.class),rs.getObject("thread_id",UUID.class),rs.getString("normalized_text"),rs.getString("material_hash").strip(),
            RequestStatus.valueOf(rs.getString("request_status")),json(rs.getString("authoritative_result")),rs.getString("failure_code"),
            instant(rs,"created_at"),instant(rs,"updated_at"),instant(rs,"completed_at"));}
    private JsonNode json(String value){return value==null?null:mapper.readTree(value);}
    private static Instant instant(ResultSet rs,String column)throws SQLException{OffsetDateTime value=rs.getObject(column,OffsetDateTime.class);return value==null?null:value.toInstant();}
}
