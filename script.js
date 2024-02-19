document.addEventListener('DOMContentLoaded', () => {
  const elements = document.querySelectorAll('.avatar, h1, .motto, .button');
  let delay = 0;
  elements.forEach(el => {
    setTimeout(() => {
      el.classList.add('fade-in');
    }, delay);
    delay += 500; // Increment the delay for the next element
  });
});
