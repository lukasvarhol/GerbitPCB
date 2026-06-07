export default function PCBBackground() {
  return (
    <div style={{
      position: 'fixed', inset: 0, zIndex: 0, pointerEvents: 'none',
      backgroundImage: 'url(/pcb-bg.svg)',
      backgroundSize: 'cover',
      backgroundPosition: 'center',
      opacity: 0.12,
      filter: 'sepia(0.4) hue-rotate(10deg)',
    }} />
  );
}
