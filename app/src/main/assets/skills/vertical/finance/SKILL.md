---
name: finance
description: Check balances and transactions via banking apps (UI automation)
version: "1.0"
author: MobileClaw Built-in
tools_required: ui, app, notifications
---
# Finance & Banking
## Role
You help the user check account balances, review recent transactions, and manage finances by automating banking and finance apps via UI interaction. This skill uses accessibility-based UI automation since banks do not provide public APIs.
Keywords: "balance", "bank", "transfer", "餘額", "帳戶", "account", "transaction", "信用卡", "credit card", "statement"

## SECURITY WARNING
This skill accesses sensitive financial data through banking apps using accessibility services. Important safeguards:
- NEVER screenshot or log account numbers, passwords, or balances to any external service
- NEVER store financial data persistently
- NEVER initiate transfers or payments without explicit user confirmation and verification
- All financial data is read-only by default — report to user, then forget
- If the app requires biometric/PIN authentication, hand control back to the user
## Standard Workflows
### Check Bank Account Balance
Open a banking app and read the balance.
1. Launch the banking app:
   ```
   app action="launch" package="{bank_app_package}"
   ```
   Common Taiwan banking app packages:
   - Cathay United Bank: `com.cathaybk.mymobibank.activity`
   - CTBC Bank: `com.chinatrust.mobilebank`
   - E.Sun Bank: `com.esunbank`
   - Fubon Bank: `com.fubon.mobilebank`
   - Taishin Bank: `com.taishinbank.mobilebank`
   - First Bank: `com.firstbank.mobilebank`
   - Line Bank: `com.linebank.tw`
2. Wait for the app to load. If biometric/PIN prompt appears:
   ```
   ui action="read_screen"
   ```
   If login is required, tell the user: "Please authenticate in the banking app. Let me know when you're on the main screen."
3. Once on the main screen, find the balance:
   ```
   ui action="find" text="餘額" OR text="Balance" OR text="可用餘額"
   ui action="read_screen"
   ```
4. Extract the balance amount from the screen
5. Report the balance to the user verbally
6. Return to MobileClaw:
   ```
   app action="launch" package="com.mobileclaw.app"
   ```
### Check Recent Transactions
1. Launch banking app and authenticate (same as above)
2. Navigate to transaction history:
   ```
   ui action="find" text="交易明細" OR text="Transactions" OR text="近期交易" OR text="帳戶明細"
   ui action="tap" element={transactions_tab}
   ```
3. Read the transaction list:
   ```
   ui action="read_screen"
   ```
4. Extract: date, description, amount, balance for each transaction
5. Scroll down for more if needed:
   ```
   ui action="scroll" direction="down"
   ui action="read_screen"
   ```
6. Present transactions in a clean list format
7. Return to MobileClaw
### Check Credit Card Statement
1. Launch banking or credit card app
2. Navigate to credit card section:
   ```
   ui action="find" text="信用卡" OR text="Credit Card"
   ui action="tap" element={credit_card_tab}
   ```
3. Find current statement:
   ```
   ui action="find" text="本期帳單" OR text="Current Statement" OR text="未出帳"
   ui action="read_screen"
   ```
4. Extract: total amount due, payment due date, minimum payment
5. If user wants details, navigate into the statement and read individual charges
6. Report summary to user
7. Return to MobileClaw
### Check Stock / Investment Portfolio
1. Launch investment app:
   ```
   app action="launch" package="{investment_app_package}"
   ```
   Common packages:
   - Fubon Securities: `com.fubon.securities`
   - Cathay Securities: `com.cathaysec.mobileapp`
   - Yuanta Securities: `com.yuanta.securities`
   - SinoPac Securities: `com.sinopac.securities`
2. Navigate to portfolio:
   ```
   ui action="find" text="庫存" OR text="Portfolio" OR text="持股"
   ui action="tap" element={portfolio_tab}
   ui action="read_screen"
   ```
3. Extract: stock name/code, quantity, current price, gain/loss
4. Present portfolio summary with total value and daily change
5. Return to MobileClaw
### Monitor Delivery Notifications for Financial Apps
Track bank notifications for transactions.
1. Use notifications tool to read recent financial notifications:
   ```
   notifications action="read" package="{bank_app_package}"
   ```
2. Filter for transaction alerts: look for keywords like "消費", "入帳", "轉帳", "扣款"
3. Summarize: "You have 3 new transaction alerts — NT$150 at 7-Eleven, NT$2,400 at PChome..."
### Quick Balance Summary (Multi-Account)
Check balances across multiple banks.
1. For each configured bank app, repeat the Check Balance workflow
2. Compile a summary:
   - Bank A checking: NT$XX,XXX
   - Bank B savings: NT$XX,XXX
   - Credit card outstanding: NT$X,XXX
   - Total liquid assets: NT$XXX,XXX
3. Note: This requires launching and authenticating each app sequentially, so it may take a few minutes
## Guidelines
- ALWAYS hand control to user for authentication (biometrics, PIN, password)
- NEVER attempt to type passwords or PINs — this is a hard rule
- Read data, report it verbally, then forget it — no persistent storage of financial data
- If a banking app has an anti-screenshot/anti-automation policy, respect it and tell the user
- Some banking apps may detect accessibility service usage and block it — inform the user if this happens
- Always return to MobileClaw after finishing with a banking app
- For currency, default to NT$ (New Taiwan Dollar) unless the user specifies otherwise
- Round amounts appropriately — don't show excessive decimal places for display currencies
- If the user asks about transfers or payments, guide them but let them perform the actual action manually
