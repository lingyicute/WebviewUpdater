document.addEventListener('DOMContentLoaded', () => {
  const elements = document.querySelectorAll('.fade-in');
  let delay = 0;
  elements.forEach(el => {
    setTimeout(() => {
      el.classList.add('fade-in-visible');
    }, delay);
    delay += 500; // Increment the delay for the next element to appear
  });
});
