const customerForm = document.querySelector('#customer-form');
customerForm?.addEventListener('submit', () => {
  const button = customerForm.querySelector('button[type="submit"]');
  button.disabled = true;
  document.querySelector('#load-status').textContent = 'Loading goals and current offers…';
});
const form = document.querySelector('#question-form');
const explain = document.querySelector('#explain-goals');
async function ask(path) {
  const status = document.querySelector('#question-status');
  const results = document.querySelector('#goal-results');
  const buttons = [...document.querySelectorAll('#ask-goals button')];
  const timeout = new AbortController();
  const timer = setTimeout(() => timeout.abort(), 65000);
  buttons.forEach(b => { b.disabled = true; });
  results.setAttribute('aria-busy', 'true');
  status.textContent = 'Working on your question. Your loaded goals remain available below…';
  try {
    const response = await fetch(path, {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, signal: timeout.signal,
      body: JSON.stringify({ customerId: form.elements.customerId.value, question: form.elements.question.value })
    });
    if (!response.ok) throw new Error(response.status === 409
      ? 'These results have expired or another customer was loaded. Refresh this customer’s goals first.'
      : 'The request could not be completed. Please retry.');
    // Server-rendered fragment uses escaped Thymeleaf text for every model/data field.
    results.innerHTML = await response.text();
    status.textContent = 'Results updated below.';
  } catch (error) {
    status.textContent = error.name === 'AbortError'
      ? 'AI is taking too long. Your original results are still available; try again later.' : error.message;
  } finally {
    clearTimeout(timer);
    buttons.forEach(b => { b.disabled = false; });
    results.setAttribute('aria-busy', 'false');
  }
}
form?.addEventListener('submit', event => { event.preventDefault(); ask('/experience/question'); });
document.querySelectorAll('.question-example').forEach(button => button.addEventListener('click', () => {
  form.elements.question.value = button.dataset.question;
  form.requestSubmit();
}));
explain?.addEventListener('click', () => ask('/experience/explain'));
