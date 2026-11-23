RigWorks.register('rigworks:starpop_motion', function (frame) {
  var policy = frame.mappings.motion || {};
  if (policy.driver === 'vanilla') {
    frame.defaultAnimation();
    return;
  }

  // The profile owns clip selection, cadence, swing phase and native fallbacks.
  frame.defaultMotion();
});
