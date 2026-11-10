const customerForm=document.querySelector('#customer-form');
customerForm?.addEventListener('submit',()=>{customerForm.querySelector('button').disabled=true;document.querySelector('#load-status').textContent='Loading customer details…';});
const form=document.querySelector('#chat-form');
const messages=document.querySelector('#chat-messages');
const status=document.querySelector('#chat-status');
let busy=false;
function payload(question=''){return {customerId:form.dataset.customer,conversationId:form.dataset.conversation,token:form.dataset.token,question};}
function userBubble(text){const article=document.createElement('article');article.className='chat-message user';const title=document.createElement('strong');title.textContent='You';const p=document.createElement('p');p.textContent=text;article.append(title,p);messages.append(article);}
function controls(disabled){document.querySelectorAll('#conversation button').forEach(b=>b.disabled=disabled);messages.setAttribute('aria-busy',String(disabled));}
async function send(){
  if(busy||!form.reportValidity())return;
  const text=form.elements.question.value.trim();if(!text)return;
  busy=true;userBubble(text);form.elements.question.value='';controls(true);status.textContent='Thinking and checking your customer-scoped information…';
  const abort=new AbortController();const timer=setTimeout(()=>abort.abort(),75000);
  try{
    const response=await fetch('/experience/chat',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(payload(text)),signal:abort.signal});
    if(!response.ok)throw new Error(response.status===403?'Customer selection changed. Reload this customer before continuing.':response.status===409?'A reply is already being prepared. Please wait.':'The assistant could not complete the request. Please retry.');
    const html=await response.text();const template=document.createElement('template');template.innerHTML=html;
    // Only server-rendered fragments are inserted; all user/model facts are escaped by Thymeleaf.
    const history=template.content.querySelector('[data-assistant-history]');
    const reply=template.content.querySelector('[data-assistant-reply]');
    if(history)messages.replaceChildren(...history.childNodes);
    else if(reply)messages.append(...reply.childNodes);
    else throw new Error('Invalid assistant response.');
    const contact=template.content.querySelector('[data-contact-content]');document.querySelector('#contact-panel').replaceChildren(...(contact?[...contact.childNodes]:[]));
    while(messages.querySelectorAll('.chat-message:not(.welcome-message)').length>12){messages.querySelector('.chat-message:not(.welcome-message)').remove();}
    status.textContent='';
  }catch(error){status.textContent=error.name==='AbortError'?'The assistant is taking too long. Please retry once the model has loaded.':error.message;}
  finally{clearTimeout(timer);busy=false;controls(false);form.elements.question.focus();}
}
form?.addEventListener('submit',event=>{event.preventDefault();send();});
document.querySelectorAll('.chat-example').forEach(button=>button.addEventListener('click',()=>{form.elements.question.value=button.dataset.question;send();}));
document.querySelector('#reset-chat')?.addEventListener('click',async()=>{
 if(busy)return;controls(true);
 try{const response=await fetch('/experience/reset',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(payload())});if(!response.ok)throw new Error();window.location.reload();}
 catch{status.textContent='Unable to reset the conversation. Reload the customer and retry.';controls(false);}
});
