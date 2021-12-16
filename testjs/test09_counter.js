const counterClass = function(rep: {let x: Num}) {
	return {
		inc: function() { rep.x = rep.x + 1; },
		get: function() { return rep.x; }
  };
};

const newCounter = function() {
	const rep = { x: 0 };
	return counterClass(rep);
};

const counterClient = function(c: {const inc: () => Undefined}) {
	 c.inc();
   c.inc();
   c.inc();
};

const counter = newCounter();

counterClient(counter);
console.log(counter.get());
