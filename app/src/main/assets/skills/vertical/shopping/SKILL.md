---
name: shopping
description: Search products, compare prices, and track orders
version: "1.0"
author: MobileClaw Built-in
tools_required: web, ui, app
---
# Shopping & Price Comparison
## Role
You help the user find products, compare prices across stores, and track deliveries. Use a combination of web search, app launching, and UI automation to get the best deals.
Keywords: "buy", "price", "shopping", "比價", "購物", "cheapest", "deal", "coupon", "折扣", "優惠", "order status", "delivery", "包裹"
## Standard Workflows
### Price Search and Comparison
Find the best price for a product across multiple sources.
1. Search the web for pricing:
   ```
   web action="search" query="{product name} price comparison {current year}"
   ```
2. Search on specific platforms:
   ```
   web action="search" query="{product name} site:amazon.com OR site:shopee.tw OR site:momo.com"
   ```
3. Open the most relevant result to extract exact prices:
   ```
   web action="open_url" url="{result_url}"
   ```
4. Compile a comparison table with: store name, price, shipping cost, estimated delivery
5. Recommend the best option considering total cost and reliability
### Search on a Shopping App
Open a specific shopping app and search for a product.
1. Launch the shopping app:
   ```
   app action="launch" package="com.shopee.tw"
   ```
   Common shopping app packages:
   - Shopee TW: `com.shopee.tw`
   - Momo: `com.momo.mobile`
   - PChome: `com.pchome.24h`
   - Amazon: `com.amazon.mShop.android.shopping`
   - Rakuten: `jp.co.rakuten.android`
2. Wait for app to load, then use ui tool to find search bar:
   ```
   ui action="find" text="搜尋" OR contentDescription="Search"
   ```
3. Tap on search bar and type product name:
   ```
   ui action="tap" element={search_bar}
   ui action="type" text="{product name}"
   ui action="tap" element={search_button}
   ```
4. Read search results using ui tool:
   ```
   ui action="read_screen"
   ```
5. Extract product names, prices, ratings, and sold counts
6. Report findings to user
### Track an Order / Delivery
Check delivery status of an existing order.
1. Option A — Check via shopping app:
   ```
   app action="launch" package="{shopping_app_package}"
   ```
   Navigate to "My Orders" or "訂單":
   ```
   ui action="find" text="我的訂單" OR text="My Orders" OR text="訂單查詢"
   ui action="tap" element={orders_tab}
   ```
   Read the order list:
   ```
   ui action="read_screen"
   ```
2. Option B — Check via tracking number on web:
   ```
   web action="search" query="tracking {tracking_number}"
   ```
   Or go directly to carrier:
   ```
   web action="open_url" url="https://www.17track.net/en/track#nums={tracking_number}"
   ```
3. Option C — Check delivery app directly:
   Common delivery apps:
   - 7-11 ibon: `com.openpoint.app`
   - FamilyMart: `com.familymart.app`
   - Taiwan Post: `tw.com.post.epost`
4. Report: order status, current location, estimated delivery date
### Find Coupons and Deals
1. Search for coupons:
   ```
   web action="search" query="{store name} coupon code {current month} {current year}"
   ```
2. Search for deals:
   ```
   web action="search" query="{product name} deal discount"
   ```
3. Check store-specific deal pages within apps:
   ```
   app action="launch" package="{store_app}"
   ui action="find" text="優惠" OR text="領券" OR text="Deals"
   ui action="tap" element={deals_section}
   ui action="read_screen"
   ```
4. Compile available coupons/deals and present to user
### Add to Cart (with Confirmation)
Help the user add an item to their cart in a shopping app.
1. Ensure the user is viewing the desired product in the app
2. Find the "Add to Cart" button:
   ```
   ui action="find" text="加入購物車" OR text="Add to Cart"
   ```
3. ASK THE USER FOR CONFIRMATION before tapping
4. After confirmation:
   ```
   ui action="tap" element={add_to_cart_button}
   ```
5. Confirm the item was added by reading the screen response
### Price Alert (Manual Check)
When the user wants to monitor a price:
1. Note the product URL, target price, and current price
2. Tell the user: "I can check the price again whenever you ask. Say 'check price for {product}' and I'll look it up."
3. On follow-up checks, re-fetch the URL and compare against target price
## Guidelines
- Always show prices with currency (NT$, US$, etc.)
- Include shipping costs in total price comparison — the cheapest product isn't always the best deal
- Be aware of regional store availability (user is likely in Taiwan based on app preferences)
- Never auto-purchase or complete checkout without explicit user confirmation
- When comparing prices, note if items are genuine/official vs third-party sellers
- For electronics, check warranty terms across sellers
- If a deal seems too good to be true, warn the user about potential scams
- Always return to MobileClaw after finishing app interactions
