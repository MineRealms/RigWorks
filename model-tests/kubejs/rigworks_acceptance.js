// Installed only by the explicit -PclientKubeJsSmoke acceptance task.
if (typeof RigWorksAcceptance !== 'undefined') {
RigWorks.register('acceptance_top_level', function (frame) { frame.defaultMotion() })
RigWorksAcceptance.onClient(function () {
  function check(value, message) { if (!value) throw new Error(message) }
  var probe = null
  try {
    check(RigWorks.controllers().contains('acceptance_top_level'), 'top-level registration failed: '+RigWorks.controllers())
    RigWorks.unregister('acceptance_top_level')
    check(RigWorks.controllers().contains(AssimpPlayerActions.config.id), 'canonical demo did not load in Rhino')
    probe = RigWorksAcceptance.create()
    var model = probe.handle()
    var mapped = function (values) { return {get: function (key) { return values[key] }} }
    var sword = mapped({id: 'minecraft:diamond_sword', attackType: 'sword', tags: [], useAnimation: 'none'})
    var empty = mapped({id: 'minecraft:air', attackType: 'fist', tags: [], useAnimation: 'none'})
    var facts = {tick: 1, time: 0.05, swingGeneration: 1, swingActive: true, swingProgress: 0,
      swingArm: 'right', primaryArm: 'right', swingHand: 'main', swingItem: sword,
      mainItem: sword, offItem: empty, onGround: true, sprinting: true}
    var state = {}, stateMap = {get: function (key) { return state[key] }, put: function (key, value) { state[key] = value }}
    var names = {hips:'Hips',chest:'Chest',spine:'Spine',head:'Head',right_upper_arm:'UpperArm.R',left_upper_arm:'UpperArm.L',
      right_forearm:'Forearm.R',left_forearm:'Forearm.L',right_hand:'Hand.R',left_hand:'Hand.L',
      right_thigh:'Thigh.R',left_thigh:'Thigh.L',right_shin:'Shin.R',left_shin:'Shin.L',right_foot:'Foot.R',left_foot:'Foot.L'}
    var frame = {input:function(){return mapped(facts)},state:function(){return stateMap},model:function(){return model},
      bone:function(role){return names[role] || null},defaultMotion:function(){}}
    var expected = ['slash_up','slash_down','slash_inward','slash_outward','slash_spin']
    for (var hit=0;hit<expected.length;hit++) {
      facts.tick=hit+1;facts.swingGeneration=hit+1;facts.swingProgress=0
      AssimpPlayerActions.controller(frame)
      check(state.action===expected[hit], 'FBX combo sequence '+hit)
      var start=model.node('Hand.R')
      facts.swingProgress=.45
      AssimpPlayerActions.controller(frame)
      var end=model.node('Hand.R')
      var distance=Math.abs(Number(start.get('x'))-Number(end.get('x')))+Math.abs(Number(start.get('y'))-Number(end.get('y')))+Math.abs(Number(start.get('z'))-Number(end.get('z')))
      check(distance>.1, 'FBX hand did not move through '+expected[hit])
    }
    model.clearScriptControls()
    RigWorks.unregister(AssimpPlayerActions.config.id)
    RigWorks.register('acceptance', function (frame) { frame.defaultMotion() })
    check(String(RigWorks.active())==='acceptance', 'registered controller was not selected')
    RigWorks.useDefault().unregister('acceptance')
    RigWorksAcceptance.success()
  } catch (error) {
    RigWorksAcceptance.failure(String(error))
    throw error
  } finally {
    if (probe) probe.close()
  }
})
}
