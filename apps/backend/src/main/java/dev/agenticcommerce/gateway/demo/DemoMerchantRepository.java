package dev.agenticcommerce.gateway.demo;

import static dev.agenticcommerce.gateway.demo.DemoMerchantModels.*;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Repository
public class DemoMerchantRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    public DemoMerchantRepository(JdbcClient jdbc,ObjectMapper mapper){this.jdbc=jdbc;this.mapper=mapper;}

    public void saveProfile(UUID merchantId,String code,boolean cancel,boolean returns,
            boolean perishableReturns,int deliveryMinutes){jdbc.sql("""
            INSERT INTO demo_merchant_profile(merchant_id,profile_code,cancellation_allowed,
                returns_allowed,perishable_returns_allowed,delivery_minutes)
            VALUES (:m,:code,:cancel,:returns,:perishable,:delivery)
            ON CONFLICT (merchant_id) DO UPDATE SET profile_code=EXCLUDED.profile_code,
                cancellation_allowed=EXCLUDED.cancellation_allowed,
                returns_allowed=EXCLUDED.returns_allowed,
                perishable_returns_allowed=EXCLUDED.perishable_returns_allowed,
                delivery_minutes=EXCLUDED.delivery_minutes
            """).param("m",merchantId).param("code",code).param("cancel",cancel)
            .param("returns",returns).param("perishable",perishableReturns)
            .param("delivery",deliveryMinutes).update();}

    public Optional<Profile> profile(String merchantKey){return jdbc.sql("""
            SELECT m.merchant_id,m.merchant_key,m.display_name,p.* FROM merchant m
            JOIN demo_merchant_profile p ON p.merchant_id=m.merchant_id
            WHERE m.merchant_key=:key
            """).param("key",merchantKey).query((rs,n)->new Profile(rs.getObject("merchant_id",UUID.class),
                    rs.getString("merchant_key"),rs.getString("display_name"),rs.getString("profile_code"),
                    rs.getBoolean("cancellation_allowed"),rs.getBoolean("returns_allowed"),
                    rs.getBoolean("perishable_returns_allowed"),rs.getInt("delivery_minutes"))).optional();}

    public boolean isDemoMerchant(UUID merchantId){return jdbc.sql(
            "SELECT EXISTS(SELECT 1 FROM demo_merchant_profile WHERE merchant_id=:merchant)")
            .param("merchant",merchantId).query(Boolean.class).single();}

    public void initializeInventory(UUID merchantId,UUID catalogueVersionId){jdbc.sql("""
            INSERT INTO demo_merchant_inventory(merchant_id,catalogue_version_id,product_id,merchant_sku,available_quantity)
            SELECT p.merchant_id,p.catalogue_version_id,p.product_id,p.merchant_sku,COALESCE(c.stock_quantity,0)
            FROM merchant_product p JOIN merchant_product_commerce_state c
              ON c.merchant_id=p.merchant_id AND c.catalogue_version_id=p.catalogue_version_id AND c.product_id=p.product_id
            WHERE p.merchant_id=:m AND p.catalogue_version_id=:v
            ON CONFLICT (merchant_id,product_id) DO NOTHING
            """).param("m",merchantId).param("v",catalogueVersionId).update();}

    public Optional<InventoryProduct> product(UUID merchantId,String sku,UUID productId,boolean lock){
        String suffix=lock?" FOR UPDATE OF i":"";
        return jdbc.sql("""
            SELECT p.product_id,p.catalogue_version_id,p.merchant_sku,p.canonical_name,p.variant,p.category,
                   c.price_minor,c.currency,i.available_quantity
            FROM merchant_product p JOIN catalogue_version v ON v.catalogue_version_id=p.catalogue_version_id
            JOIN merchant_product_commerce_state c ON c.merchant_id=p.merchant_id AND c.product_id=p.product_id
            JOIN demo_merchant_inventory i ON i.merchant_id=p.merchant_id AND i.product_id=p.product_id
            WHERE p.merchant_id=:m AND v.status='PUBLISHED'
              AND (:sku IS NULL OR lower(p.merchant_sku)=lower(:sku)) AND (:pid IS NULL OR p.product_id=:pid)
            ORDER BY v.version_number DESC LIMIT 1
            """+suffix).param("m",merchantId).param("sku",sku).param("pid",productId)
                .query(DemoMerchantRepository::mapProduct).optional();}

    public void decrement(UUID merchantId,UUID productId,int quantity){int changed=jdbc.sql("""
            UPDATE demo_merchant_inventory SET available_quantity=available_quantity-:q,
                inventory_version=inventory_version+1,updated_at=CURRENT_TIMESTAMP
            WHERE merchant_id=:m AND product_id=:p AND available_quantity>=:q
            """).param("q",quantity).param("m",merchantId).param("p",productId).update();
        if(changed!=1)throw new IllegalStateException("INSUFFICIENT_STOCK");}
    public void restore(UUID merchantId,JsonNode lines){for(JsonNode line:lines)jdbc.sql("""
            UPDATE demo_merchant_inventory SET available_quantity=available_quantity+:q,
                inventory_version=inventory_version+1,updated_at=CURRENT_TIMESTAMP
            WHERE merchant_id=:m AND product_id=:p
            """).param("q",line.path("quantity").asInt()).param("m",merchantId)
                .param("p",UUID.fromString(line.path("productId").asText())).update();}

    public Optional<Order> orderByOperation(UUID merchantId,String operationId,boolean lock){return jdbc.sql("""
            SELECT * FROM demo_merchant_order WHERE merchant_id=:m AND merchant_operation_id=:o
            """+(lock?" FOR UPDATE":"")).param("m",merchantId).param("o",operationId).query(this::mapOrder).optional();}
    public Optional<Order> orderByIdentity(UUID merchantId,String orderId,boolean lock){return jdbc.sql("""
            SELECT * FROM demo_merchant_order WHERE merchant_id=:m AND merchant_order_id=:o
            """+(lock?" FOR UPDATE":"")).param("m",merchantId).param("o",orderId).query(this::mapOrder).optional();}
    public Order createOrder(UUID merchantId,String operationId,String orderId,String hash,String customer,
            JsonNode lines,long total,String currency){return jdbc.sql("""
            INSERT INTO demo_merchant_order(merchant_id,merchant_operation_id,merchant_order_id,request_hash,
                customer_reference,line_items,total_minor,currency,order_state)
            VALUES (:m,:op,:oid,:hash,:customer,CAST(:lines AS jsonb),:total,:currency,'PLACED') RETURNING *
            """).param("m",merchantId).param("op",operationId).param("oid",orderId).param("hash",hash)
            .param("customer",customer).param("lines",mapper.writeValueAsString(lines)).param("total",total)
            .param("currency",currency)
            .query(this::mapOrder).single();}
    public Order updateState(Order order,String state,boolean release){return jdbc.sql("""
            UPDATE demo_merchant_order SET order_state=:state,stock_released=:release,updated_at=CURRENT_TIMESTAMP
            WHERE demo_order_id=:id AND order_state=:expected RETURNING *
            """).param("state",state).param("release",release).param("id",order.id())
            .param("expected",order.state()).query(this::mapOrder).optional().orElseThrow();}

    private static InventoryProduct mapProduct(ResultSet rs,int n)throws SQLException{return new InventoryProduct(
            rs.getObject("product_id",UUID.class),rs.getObject("catalogue_version_id",UUID.class),
            rs.getString("merchant_sku"),rs.getString("canonical_name"),rs.getString("variant"),
            rs.getString("category"),rs.getLong("price_minor"),rs.getString("currency").strip(),
            rs.getLong("available_quantity"));}
    private Order mapOrder(ResultSet rs,int n)throws SQLException{return new Order(rs.getObject("demo_order_id",UUID.class),
            rs.getObject("merchant_id",UUID.class),rs.getString("merchant_operation_id"),rs.getString("merchant_order_id"),
            rs.getString("request_hash").strip(),rs.getString("customer_reference"),mapper.readTree(rs.getString("line_items")),
            rs.getLong("total_minor"),rs.getString("currency").strip(),rs.getString("order_state"),
            rs.getBoolean("stock_released"),rs.getObject("created_at",OffsetDateTime.class).toInstant());}
}
