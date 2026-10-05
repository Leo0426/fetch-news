(() => {
  const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)');
  const storage = {
    get(key) { try { return localStorage.getItem(key); } catch (_) { return null; } },
    set(key, value) { try { localStorage.setItem(key, value); } catch (_) {} },
    remove(key) { try { localStorage.removeItem(key); } catch (_) {} }
  };

  function notify(message, kind = 'success') {
    let region = document.querySelector('.ui-toast-region');
    if (!region) {
      region = document.createElement('div');
      region.className = 'ui-toast-region';
      region.setAttribute('aria-label', '操作反馈');
      document.body.appendChild(region);
    }
    const toast = document.createElement('div');
    toast.className = 'ui-toast' + (kind === 'error' ? ' ui-toast--error' : '');
    toast.setAttribute('role', kind === 'error' ? 'alert' : 'status');
    toast.setAttribute('aria-atomic', 'true');
    const icon = document.createElement('span');
    icon.className = 'ui-toast-icon';
    icon.setAttribute('aria-hidden', 'true');
    icon.textContent = kind === 'error' ? '!' : '✓';
    const text = document.createElement('span');
    text.textContent = message;
    const close = document.createElement('button');
    close.type = 'button';
    close.setAttribute('aria-label', '关闭提示');
    close.textContent = '×';
    let timer;
    const dismiss = () => { clearTimeout(timer); toast.remove(); };
    close.addEventListener('click', dismiss);
    toast.append(icon, text, close);
    region.appendChild(toast);
    while (region.children.length > 3) region.firstElementChild.remove();
    const schedule = () => { timer = setTimeout(dismiss, kind === 'error' ? 8000 : 4000); };
    toast.addEventListener('mouseenter', () => clearTimeout(timer));
    toast.addEventListener('mouseleave', schedule);
    toast.addEventListener('focusin', () => clearTimeout(timer));
    toast.addEventListener('focusout', schedule);
    schedule();
  }

  window.FetchNewsUI = {
    storage,
    notify,
    motionBehavior: () => reducedMotion.matches ? 'auto' : 'smooth'
  };

  function initialize() {
    const candidates = document.querySelectorAll('.reveal');
    if ('IntersectionObserver' in window && !reducedMotion.matches) {
      const observer = new IntersectionObserver(entries => {
        entries.forEach(entry => {
          if (!entry.isIntersecting) return;
          entry.target.classList.remove('ui-pending');
          entry.target.classList.add('in');
          observer.unobserve(entry.target);
        });
      }, { threshold: 0.02, rootMargin: '0px 0px 40px 0px' });
      candidates.forEach(element => {
        element.classList.add('ui-pending');
        observer.observe(element);
      });
      reducedMotion.addEventListener('change', () => {
        if (!reducedMotion.matches) return;
        observer.disconnect();
        candidates.forEach(element => element.classList.remove('ui-pending'));
      });
    }

    const progress = document.getElementById('rd-progress-bar');
    if (progress) {
      let scheduled = false;
      const update = () => {
        const total = document.documentElement.scrollHeight - window.innerHeight;
        const percentage = total > 0 ? Math.min(100, Math.max(0, window.scrollY / total * 100)) : 0;
        progress.style.width = percentage + '%';
        progress.classList.toggle('done', percentage >= 99.5);
        scheduled = false;
      };
      const schedule = () => {
        if (!scheduled) { scheduled = true; requestAnimationFrame(update); }
      };
      window.addEventListener('scroll', schedule, { passive: true });
      window.addEventListener('resize', schedule);
      if ('ResizeObserver' in window) new ResizeObserver(schedule).observe(document.body);
      update();
    }
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', initialize);
  else initialize();
})();
