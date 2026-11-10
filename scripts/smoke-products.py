#!/usr/bin/env python3
"""Read-only smoke checks: customer profile first, goal-only product policy and PLCC gating.
Run after geocoding succeeds. Does not start campaigns, send messages or require a chat model.
"""
import json,os,urllib.request,urllib.parse
BASE=os.environ.get('LIFESYNC_SMOKE_URL','http://127.0.0.1:8080').rstrip('/')
def rpc(method,params):
    req=urllib.request.Request(BASE+'/mcp',data=json.dumps(dict(jsonrpc='2.0',id=1,method=method,params=params)).encode(),headers={
        'Content-Type':'application/json','Accept':'application/json, text/event-stream','MCP-Protocol-Version':'2025-03-26'})
    with urllib.request.urlopen(req,timeout=30) as r:result=json.load(r)
    assert 'error' not in result,result
    return result['result']
def tool(name,args):
    result=rpc('tools/call',{'name':name,'arguments':args});assert not result['isError'],result
    return json.loads(result['content'][0]['text'])
rpc('initialize',{'protocolVersion':'2025-03-26','capabilities':{},'clientInfo':{'name':'lifesync-smoke','version':'1'}})
listing=rpc('tools/list',{})
assert {t['name'] for t in listing['tools']} >= {'get_customer_profile','list_customer_goals','find_plcc_merchants'}
for i in range(1,4):
    cid=f'C{i:03d}';args={'customerId':cid}
    profile=tool('get_customer_profile',args);assert profile['firstName'] and profile['lastName']
    assert 'goals' not in profile and 'ecn' not in profile
    goals=tool('list_customer_goals',args);assert len(goals['goals'])==4
    assert all(not g['providers'] for g in goals['goals'])
    assert {g['productId'] for g in goals['goals']}=={'PLCC','HOME_PURCHASE','HOME_REFINANCE','AUTO_LOAN'}
    assert all(g['goalId'].startswith(f'PG-{i:03d}-') for g in goals['goals'])
    details=tool('get_customer_subgraph',args)
    assert all(not g['providers'] for g in details['goals'] if g['productId']!='PLCC')
    plcc=tool('find_plcc_merchants',dict(customerId=cid,goalIds=[f'PG-{i:03d}-1'],limit=5))['goals'][0]
    if i!=1:assert plcc['status']=='PLCC_ACCOUNT_RESTRICTED' and not plcc['providers']
    else:
        assert len(plcc['providers'])==3, 'Geocode fixture addresses before full provider readiness checks'
        distances=[p['distanceMiles'] for p in plcc['providers']];assert distances==sorted(distances)
        for p in plcc['providers']:
            assert all(o['productId']=='PLCC' and o['merchantId']==p['merchantId'] and o['customerId'] in (None,cid) and o['offerId']!='PO-EXPIRED' for o in p['offers'])
    with urllib.request.urlopen(BASE+'/experience?'+urllib.parse.urlencode(args),timeout=30) as r:html=r.read().decode()
    assert 'Customer details' in html or 'CUSTOMER DETAILS' in html
    assert profile['firstName'] in html and profile['lastName'] in html
    assert all(g['description'] not in html for g in goals['goals']), 'Goals shown before customer asks'
    assert 'class="merchant"' not in html
    print(cid,'profile-first UI, all basic goals, PLCC-only details and account gates passed')
print('Read-only MCP and initial customer experience checks passed; chat and manual campaign checks are separate.')
