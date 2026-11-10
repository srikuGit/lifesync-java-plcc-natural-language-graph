"""Generate reproducible fictional identities and address-only fixtures; never invent coordinates.
Public civic addresses are illustrative locations, not actual residences/business associations.
"""
import json, random, re
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]/'data/input'
rng=random.Random(9102026)
used=set()
def ecn():
    while True:
        n=str(rng.randrange(100000000000,999999999999))
        if n not in used: used.add(n); return n

def read(name): return json.loads((ROOT/name).read_text())
def save(name,value): (ROOT/name).write_text(json.dumps(value,indent=2)+'\n')
addresses={
 'Seattle':('1000 4th Ave','98104'), 'Shoreline':('17500 Midvale Ave N','98133'),
 'Redmond':('15670 NE 85th St','98052'), 'Bothell':('18415 101st Ave NE','98011'),
 'Kirkland':('123 5th Ave','98033'), 'Issaquah':('130 E Sunset Way','98027'),
 'Bellevue':('450 110th Ave NE','98004'), 'Renton':('1055 S Grady Way','98057'),
 'Tacoma':('747 Market St','98402')}
# Distinct public example addresses for providers; never reuse customer anchors.
provider_addresses={
 'Seattle':('600 4th Ave','98104'), 'Shoreline':('345 NE 175th St','98155'),
 'Redmond':('15990 NE 85th St','98052'), 'Bothell':('18215 98th Ave NE','98011'),
 'Kirkland':('308 Kirkland Ave','98033'), 'Issaquah':('10 W Sunset Way','98027'),
 'Bellevue':('1111 110th Ave NE','98004'), 'Renton':('100 Mill Ave S','98057'),
 'Tacoma':('1102 Tacoma Ave S','98402')}
first=['Amelia','Oliver','Priya','Ethan','Sofia','Noah','Maya','Lucas','Isabella','Liam','Aisha','James','Elena','Henry','Zara','Leo','Nora','Arjun','Grace','Mateo']
last=['Bennett','Patel','Chen','Rivera','Morgan','Brooks','Shah','Williams','Nguyen','Foster','Reed','Sullivan','Kim','Hughes','Torres','Campbell','Ellis','Parker','Singh','Walker']
names=[a+' '+b for a in first for b in last];rng.shuffle(names)
customers=read('customers.json');mapping={}
for i,x in enumerate(customers):
 c=x['data']['attributes'];old=c['ecn'];c['ecn']=ecn();mapping[old]=c['ecn'];c['name']['fullName']=[names[i]];c['name']['firstName'],c['name']['lastName']=names[i].split(' ',1)
 a=c['addressUsageList'][0]['address'];street,zip=addresses[a['city']]
 a.update(addressLineList=[street],address_line_1=street,address_line_2=None,postalCode=zip)
 c.pop('approximateLocation',None)
 c['plccAccountStatusSource']='FICTIONAL_FIXTURE'
save('customers.json',customers)
goals=read('goals.json')
for g in goals:g['ecn']=mapping[g['ecn']]
save('goals.json',goals)
merchants=read('merchants.json')
brands=['Cedar','Evergreen','Cascadia','Rainier','Harbor','Summit','Juniper','Alder','Willow','Maple','Olympic','Sound']
for i,m in enumerate(merchants):
 category=m['merchantCategory'].replace('_',' ').title()
 name=brands[i%len(brands)]+' '+category+' '+['Collective','Market','Gallery','Partners','Outlet'][i//len(brands)]
 m.update(ecn=ecn(),legalName=name,dbaName=name)
 m['businessDescription']=re.sub('(?i)demo|synthetic','fictional',m['businessDescription'])
 street,zip=provider_addresses[m['businessCity']];m.update(businessAddressLine1=street,businessAddressLine2='',businessZipCode=zip)
 m.pop('approximateLocation',None)
save('merchants.json',merchants)
product=read('products-demo.json');idmap={}
prior={c['customerId']:c for c in product['customers']}
product['customers']=[]
for i,entry in enumerate(customers):
 source=entry['data']['attributes'];cid=source['customerId']
 if cid in prior: source['plccAccountStatus']=prior[cid]['plccAccountStatus']
 a=source['addressUsageList'][0]['address']
 product['customers'].append(dict(customerId=cid,ecn=source['ecn'],name=source['name']['fullName'][0],firstName=source['name']['firstName'],lastName=source['name']['lastName'],
     city=a['city'],state='WA',postalCode=a['postalCode'],plccAccountStatus=source['plccAccountStatus'],
     address=f"{a['address_line_1']}, {a['city']}, WA {a['postalCode']}"))
 idmap[cid]=cid
product['goals']=[]
for i,c in enumerate(product['customers'],1):
 for j,p in enumerate(product['products'],1):
  product['goals'].append(dict(goalId=f'PG-{i:03d}-{j}',customerId=c['customerId'],
      description=['Furnish my home office','Save for a down payment to buy a home',
                   'Explore refinancing my current home','Finance a replacement family car'][j-1],
      productId=p['productId'],category=p['categories'][0]))
for p in product['products']:
 if p['productId']=='HOME_PURCHASE':p['name']='Home lending · Home purchase'
 if p['productId']=='HOME_REFINANCE':p['name']='Home lending · Refinancing'
for i,m in enumerate(product['merchants']):
 city=m['city'];street,zip=provider_addresses[city]
 m.update(ecn=ecn(),name=brands[i]+' '+{'PLCC':'Home Furnishings','HOME_PURCHASE':'Home Lending','HOME_REFINANCE':'Mortgage Partners','AUTO_LOAN':'Auto Finance'}[m['productId']],
          description={'PLCC':'Home office furniture and furnishings retailer.','HOME_PURCHASE':'Home purchase financing provider.','HOME_REFINANCE':'Mortgage refinancing provider.','AUTO_LOAN':'Vehicle purchase financing provider.'}[m['productId']],
          address=f'{street}, {city}, WA {zip}',postalCode=zip)
 m.pop('latitude',None);m.pop('longitude',None)
save('products-demo.json',product)
save('customers.json',customers)
offers=read('product-offers.json')
for o in offers:
 if o.get('customerId'):o['customerId']=idmap.get(o['customerId'],o['customerId'])
 o['title']=re.sub('(?i)Alex demo customer','Preferred customer',o['title']).replace('demo ','').replace('Demo ','')
save('product-offers.json',offers)
# Existing offer feed retains merchant IDs and customer IDs; replace display wording only.
legacy=read('offers-mock.json')
for o in legacy['offers']:
 for field in ['title','description']:
  if field in o:o[field]=re.sub('(?i)demo |synthetic ','illustrative ',o[field])
save('offers-mock.json',legacy)
