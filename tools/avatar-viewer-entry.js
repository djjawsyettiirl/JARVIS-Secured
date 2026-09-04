import * as THREE from "three";
import { FBXLoader } from "three/examples/jsm/loaders/FBXLoader.js";
import { GLTFLoader } from "three/examples/jsm/loaders/GLTFLoader.js";

const canvas = document.querySelector("#avatarCanvas");
const status = document.querySelector("#avatarStatus");
const scene = new THREE.Scene();
const camera = new THREE.PerspectiveCamera(32, 1, 0.01, 1000);
const renderer = new THREE.WebGLRenderer({ canvas, alpha: true, antialias: true, powerPreference: "high-performance" });
renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 1.75));
renderer.outputColorSpace = THREE.SRGBColorSpace;
renderer.shadowMap.enabled = true;
renderer.shadowMap.type = THREE.PCFSoftShadowMap;

scene.add(new THREE.HemisphereLight(0xbfe9ff, 0x091120, 2.6));
const key = new THREE.DirectionalLight(0x67d9ff, 4.2);
key.position.set(3, 5, 4);
key.castShadow = true;
scene.add(key);
const rim = new THREE.DirectionalLight(0xff335f, 3.2);
rim.position.set(-4, 2, -3);
scene.add(rim);

let avatar = null;
let mixer = null;
let state = "idle";
let baseY = 0;
let baseScale = 1;
const clock = new THREE.Clock();

function fitModel(object) {
  const box = new THREE.Box3().setFromObject(object);
  const size = box.getSize(new THREE.Vector3());
  const height = Math.max(size.y, 0.001);
  const scale = 2.7 / height;
  object.scale.setScalar(scale);
  const fitted = new THREE.Box3().setFromObject(object);
  const center = fitted.getCenter(new THREE.Vector3());
  object.position.x -= center.x;
  object.position.z -= center.z;
  object.position.y -= fitted.min.y + 1.35;
  camera.position.set(0, 0.05, 5.4);
  camera.lookAt(0, 0.05, 0);
  avatar = object;
  baseY = object.position.y;
  baseScale = object.scale.x;
  scene.add(object);
  status.textContent = "ONLINE";
  document.body.classList.add("loaded");
}

function failed(error) {
  console.error(error);
  status.textContent = "MODEL UNAVAILABLE";
  document.body.classList.add("loaded", "failed");
}

const params = new URLSearchParams(location.search);
const model = params.get("model") || document.body.dataset.model || "avatar.glb";
if (/\.fbx(?:$|\?)/i.test(model)) {
  new FBXLoader().load(model, fitModel, undefined, failed);
} else {
  new GLTFLoader().load(model, ({ scene: object, animations }) => {
    fitModel(object);
    if (animations.length) {
      mixer = new THREE.AnimationMixer(object);
      mixer.clipAction(animations[0]).play();
    }
  }, undefined, failed);
}

function resize() {
  const width = Math.max(canvas.clientWidth, 1);
  const height = Math.max(canvas.clientHeight, 1);
  renderer.setSize(width, height, false);
  camera.aspect = width / height;
  camera.updateProjectionMatrix();
}
window.addEventListener("resize", resize);
resize();

window.setJarvisState = next => {
  state = ["idle", "listening", "thinking", "speaking"].includes(next) ? next : "idle";
  document.body.dataset.state = state;
};
window.addEventListener("message", event => {
  if (event.data && event.data.type === "jarvis-state") window.setJarvisState(event.data.state);
});

function animate() {
  requestAnimationFrame(animate);
  const delta = Math.min(clock.getDelta(), 0.05);
  const elapsed = clock.elapsedTime;
  if (mixer) mixer.update(delta);
  if (avatar) {
    const energy = state === "speaking" ? 2.4 : state === "listening" ? 1.7 : state === "thinking" ? 1.25 : 0.65;
    avatar.rotation.y = Math.sin(elapsed * 0.32) * 0.12 + Math.sin(elapsed * energy) * 0.012;
    avatar.rotation.x = Math.sin(elapsed * energy * 0.55) * 0.008;
    avatar.position.y = baseY + Math.sin(elapsed * energy) * 0.008;
    const breath = 1 + Math.sin(elapsed * energy) * (state === "speaking" ? 0.006 : 0.0025);
    avatar.scale.set(baseScale * (1 + (breath - 1) * 0.35), baseScale * breath, baseScale);
  }
  renderer.render(scene, camera);
}
animate();
