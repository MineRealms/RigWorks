// Install by itself; registration selects this controller. Uses the current humanoid mapping.
// Crouch while standing still to enable the small left-hand IK demonstration.
RigWorks.register('tutorial:hybrid', function (frame) {
  frame.defaultAnimation()
  var model = frame.model()
  var state = frame.state()
  var input = frame.input()
  if (!state.get('tutorial_ready')) {
    var definition = {version: 1, clips: {}, poses: {}, ik: {}}
    var legs = ['left_thigh', 'left_shin', 'left_foot', 'right_thigh', 'right_shin', 'right_foot']
    var mask = {}
    for (var i = 0; i < legs.length; i++) {
      var bone = frame.bone(legs[i])
      if (bone) mask[String(bone)] = 1
    }
    var walk = frame.clip('walk')
    var clips = model.animations()
    var hasWalk = false
    for (var c = 0; c < clips.length; c++) if (String(clips[c]) === String(walk)) hasWalk = true
    if (walk && hasWalk && Object.keys(mask).length > 0) {
      definition.clips.walk = {clip: String(walk), weight: 0, loop: true, mask: mask}
      state.put('tutorial_walk', true)
    }
    var forearm = frame.bone('right_forearm')
    if (forearm) {
      var pose = {}
      pose[String(forearm)] = {rotation: [-15, 0, 0]}
      definition.poses.guard = {weight: 0, additive: true, space: 'model', bones: pose}
      state.put('tutorial_guard', true)
    }
    var upper = frame.bone('left_upper_arm')
    var lower = frame.bone('left_forearm')
    var hand = frame.bone('left_hand')
    if (upper && lower && hand) {
      var position = model.node(String(hand))
      var target = [Number(position.get('x')), Number(position.get('y')), Number(position.get('z'))]
      state.put('tutorial_x', target[0]); state.put('tutorial_y', target[1]); state.put('tutorial_z', target[2])
      definition.ik.reach = {chain: [String(upper), String(lower), String(hand)], target: target,
        enabled: false, weight: 0.6, iterations: 12, tolerance: 0.002}
      state.put('tutorial_ik', true)
    }
    model.processorDefinition('tutorial:hybrid', definition)
    state.put('tutorial_ready', true)
  }
  var special = input.get('usingItem') || input.get('swingActive') || input.get('swimming') ||
    input.get('elytra') || input.get('flying') || input.get('riding') || input.get('sleeping') || input.get('dead')
  model.processorEnabled('tutorial:hybrid', !special)
  var moving = input.get('onGround') && Number(input.get('speed')) > 0.04
  if (state.get('tutorial_walk')) {
    model.processorWeight('tutorial:hybrid', 'walk', moving && !input.get('sprinting') && !input.get('crouching') ? 1 : 0)
  }
  if (state.get('tutorial_guard')) {
    var sword = String(input.get('mainItem').get('attackType')) === 'sword'
    model.processorWeight('tutorial:hybrid', 'guard', sword && !moving ? 0.5 : 0)
  }
  if (state.get('tutorial_ik')) {
    var reaching = input.get('onGround') && input.get('crouching') && !moving && !special
    model.processorEnabled('tutorial:hybrid', 'reach', reaching)
    if (reaching) {
      model.processorTarget('tutorial:hybrid', 'reach', Number(state.get('tutorial_x')) + Math.sin(Number(input.get('time')) * 2) * 0.08,
        Number(state.get('tutorial_y')), Number(state.get('tutorial_z')) + 0.08)
    }
  }
})
