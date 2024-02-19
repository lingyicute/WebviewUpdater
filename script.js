document.addEventListener('DOMContentLoaded', () => {
  const elements = document.querySelectorAll('.avatar, h1, .motto, .nav-buttons');
  for (let i = 0; i < elements.length; i++) {
    setTimeout(() => {
      elements[i].style.opacity = 1;
    }, i * 500);
  }
});
