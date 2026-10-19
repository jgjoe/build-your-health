SELECT o.ORDER_ID,
               o.ORDER_DATE,
               o.DELIVERY_DATE,
               o.TOTAL_PRICE,
               o.RECIPIENT_NAME,
               o.SHIPPING_ADDRESS,
               oi.ITEM_ID,
               oi.PRODUCT_ID,
               oi.QUANTITY,
               oi.PRICE,
               p.PRODUCT_NAME
        FROM (
             
                    
                    SELECT *
                    FROM (
                         
        SELECT ORDER_ID,
               ORDER_DATE,
               DELIVERY_DATE,
               TOTAL_PRICE,
               RECIPIENT_NAME,
               SHIPPING_ADDRESS
        FROM ORDERS
        WHERE USER_ID = ?
        ORDER BY ORDER_DATE DESC, ORDER_ID DESC
     
                    )
                    WHERE ROWNUM <= ?
                 
        ) o
        LEFT JOIN ORDER_ITEMS oi ON oi.ORDER_ID = o.ORDER_ID
        LEFT JOIN PRODUCT p ON p.PRODUCT_ID = oi.PRODUCT_ID
        ORDER BY o.ORDER_DATE DESC, o.ORDER_ID DESC
-- userId=u000021, offset=0, size=10
