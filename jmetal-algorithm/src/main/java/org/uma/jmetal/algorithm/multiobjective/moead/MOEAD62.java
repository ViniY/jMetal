package org.uma.jmetal.algorithm.multiobjective.moead;

import org.uma.jmetal.algorithm.impl.AbstractEvolutionaryAlgorithm;
import org.uma.jmetal.algorithm.multiobjective.moead.util.MOEADUtils;
import org.uma.jmetal.component.evaluation.Evaluation;
import org.uma.jmetal.component.evaluation.impl.SequentialEvaluation;
import org.uma.jmetal.component.initialsolutioncreation.InitialSolutionsCreation;
import org.uma.jmetal.component.initialsolutioncreation.impl.RandomSolutionsCreation;
import org.uma.jmetal.component.replacement.Replacement;
import org.uma.jmetal.component.selection.MatingPoolSelection;
import org.uma.jmetal.component.selection.impl.DifferentialEvolutionMatingPoolSelection;
import org.uma.jmetal.component.termination.Termination;
import org.uma.jmetal.component.variation.Variation;
import org.uma.jmetal.operator.crossover.CrossoverOperator;
import org.uma.jmetal.operator.crossover.impl.DifferentialEvolutionCrossover;
import org.uma.jmetal.operator.mutation.MutationOperator;
import org.uma.jmetal.operator.selection.SelectionOperator;
import org.uma.jmetal.operator.selection.impl.NaryRandomSelection;
import org.uma.jmetal.problem.Problem;
import org.uma.jmetal.solution.Solution;
import org.uma.jmetal.solution.doublesolution.DoubleSolution;
import org.uma.jmetal.util.aggregativefunction.AggregativeFunction;
import org.uma.jmetal.util.neighborhood.impl.WeightVectorNeighborhood;
import org.uma.jmetal.util.observable.Observable;
import org.uma.jmetal.util.observable.ObservableEntity;
import org.uma.jmetal.util.observable.impl.DefaultObservable;
import org.uma.jmetal.util.pseudorandom.JMetalRandom;
import org.uma.jmetal.util.sequencegenerator.SequenceGenerator;
import org.uma.jmetal.util.sequencegenerator.impl.IntegerPermutationGenerator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** @author Antonio J. Nebro <antonio@lcc.uma.es> */
public class MOEAD62<S extends Solution<?>> extends AbstractEvolutionaryAlgorithm<S, List<S>>
    implements ObservableEntity {
  protected enum NeighborType {
    NEIGHBOR,
    POPULATION
  }

  private int evaluations;
  private int populationSize;
  private int offspringPopulationSize;

  private int neighborSize;
  private double neighborhoodSelectionProbability;
  private int maximumNumberOfReplacedSolutions;

  private AggregativeFunction aggregativeFunction;
  private SequenceGenerator<Integer> sequenceGenerator;

  private WeightVectorNeighborhood<S> weightVectorNeighborhood;

  private SelectionOperator<List<S>, List<S>> selectionOperator;
  private DifferentialEvolutionCrossover crossoverOperator;
  //private CrossoverOperator<S> crossoverOperator;
  private MutationOperator<S> mutationOperator;

  private int currentSubProblemId;
  private NeighborType neighborType;

  private InitialSolutionsCreation<S> initialSolutionsCreation;
  private Termination termination;
  private Evaluation<S> evaluation;
  private Replacement<S> replacement;
  private Variation<S> variation;
  private MatingPoolSelection<DoubleSolution> selection;

  private long startTime;
  private long totalComputingTime;

  private Map<String, Object> algorithmStatusData;
  private Observable<Map<String, Object>> observable;

  private Permutation permutation ;

  /** Constructor */
  public MOEAD62(
      Problem<S> problem,
      int populationSize,
      int neighborSize,
      double neighborhoodSelectionProbability,
      int maximumNumberOfReplacedSolutions,
      AggregativeFunction aggregativeFunction,
      SequenceGenerator<Integer> sequenceGenerator,
      MutationOperator<S> mutationOperator,
      Termination termination) {

    this.populationSize = populationSize;
    this.problem = problem;

    this.neighborSize = neighborSize;
    this.neighborhoodSelectionProbability = neighborhoodSelectionProbability;
    this.maximumNumberOfReplacedSolutions = maximumNumberOfReplacedSolutions;

    this.aggregativeFunction = aggregativeFunction;
    this.sequenceGenerator = sequenceGenerator;

    permutation = new Permutation(populationSize);

    this.weightVectorNeighborhood = new WeightVectorNeighborhood<S>(populationSize, neighborSize);

    selectionOperator = new NaryRandomSelection<>(2);
    selection = new DifferentialEvolutionMatingPoolSelection(3, 2, true) ;


    double cr = 1.0;
    double f = 0.5;
    this.crossoverOperator = new DifferentialEvolutionCrossover(cr, f, DifferentialEvolutionCrossover.DE_VARIANT.RAND_1_BIN);
    this.mutationOperator = mutationOperator ;

    this.initialSolutionsCreation = new RandomSolutionsCreation<>(problem, populationSize);

    this.replacement = null;

    this.variation = null ;

    //this.selection = null;

    this.termination = termination;

    this.evaluation = new SequentialEvaluation<>();

    this.offspringPopulationSize = 1;

    this.algorithmStatusData = new HashMap<>();

    this.observable = new DefaultObservable<>("MOEA/D algorithm");
  }

  @Override
  public void run() {
    startTime = System.currentTimeMillis();
    super.run();
    totalComputingTime = System.currentTimeMillis() - startTime;
  }

  @Override
  protected void initProgress() {
    evaluations = populationSize;

    for (S solution : population) {
      aggregativeFunction.update(solution.getObjectives());
    }

    algorithmStatusData.put("EVALUATIONS", evaluations);
    algorithmStatusData.put("POPULATION", population);
    algorithmStatusData.put("COMPUTING_TIME", System.currentTimeMillis() - startTime);

    observable.setChanged();
    observable.notifyObservers(algorithmStatusData);
  }

  @Override
  protected void updateProgress() {
    evaluations += offspringPopulationSize;
    algorithmStatusData.put("EVALUATIONS", evaluations);
    algorithmStatusData.put("POPULATION", population);
    algorithmStatusData.put("COMPUTING_TIME", System.currentTimeMillis() - startTime);

    observable.setChanged();
    observable.notifyObservers(algorithmStatusData);
  }

  @Override
  protected boolean isStoppingConditionReached() {
    return termination.isMet(algorithmStatusData);
  }

  @Override
  protected List<S> createInitialPopulation() {
    return initialSolutionsCreation.create();
  }

  @Override
  protected List<S> evaluatePopulation(List<S> population) {
    return evaluation.evaluate(population, getProblem());
  }

  /**
   * This method iteratively applies a {@link SelectionOperator} to the population to fill the
   * mating pool population.
   *
   * @param population
   * @return The mating pool population
   */
  @Override
  protected List<S> selection(List<S> population) {

    currentSubProblemId = sequenceGenerator.getValue();
    sequenceGenerator.generateNext();
    neighborType = chooseNeighborType();

    List<S> matingPool;
    if (neighborType.equals(MOEAD62.NeighborType.NEIGHBOR)) {
      matingPool = selectionOperator
              .execute(weightVectorNeighborhood.getNeighbors(population, currentSubProblemId));
    } else {
      matingPool = selectionOperator.execute(population);
    }

    matingPool.add(population.get(currentSubProblemId));

    return matingPool;
  }

  /**
   * This methods iteratively applies a {@link CrossoverOperator} a {@link MutationOperator} to the
   * population to create the offspring population. The population size must be divisible by the
   * number of parents required by the {@link CrossoverOperator}; this way, the needed parents are
   * taken sequentially from the population.
   *
   * <p>The number of solutions returned by the {@link CrossoverOperator} must be equal to the
   * offspringPopulationSize state variable
   *
   * @param matingPool
   * @return The new created offspring population
   */
  @Override
  protected List<S> reproduction(List<S> matingPool) {
    S currentSolution = population.get(currentSubProblemId) ;
    crossoverOperator.setCurrentSolution((DoubleSolution) currentSolution);

    List<S> offspringPopulation = (List<S>)crossoverOperator.execute((List<DoubleSolution>)matingPool);
    mutationOperator.execute(offspringPopulation.get(0));

    return offspringPopulation;
  }

  @Override
  protected List<S> replacement(List<S> population, List<S> offspringPopulation) {
    //List<S> newPopulation = replacement.replace(population, offspringPopulation);

    S newSolution = offspringPopulation.get(0) ;
    aggregativeFunction.update(newSolution.getObjectives());

    List<S> newPopulation = updateCurrentSubProblemNeighborhood(newSolution, population) ;

    return newPopulation ;
  }

  @Override
  public List<S> getResult() {
    return population ;
  }


  protected int sourceOfNeighborsSize(int subProblem) {
    if (neighborType == NeighborType.NEIGHBOR) {
      return weightVectorNeighborhood.getNeighborhood()[subProblem].length;
    } else {
      return populationSize;
    }
  }


  protected NeighborType chooseNeighborType() {
    double rnd = JMetalRandom.getInstance().nextDouble();
    NeighborType neighborType;

    if (rnd < neighborhoodSelectionProbability) {
      neighborType = NeighborType.NEIGHBOR;
    } else {
      neighborType = NeighborType.POPULATION;
    }
    return neighborType;
  }

  protected boolean maxReplacementLimitAchieved(int numberOfReplaceSolutions) {
    return numberOfReplaceSolutions >= maximumNumberOfReplacedSolutions;
  }

  protected List<S> updateCurrentSubProblemNeighborhood(
          S newSolution,
          List<S> population) {

    int[] permutedNeighborsIndexes = generatePermutationOfhNeighbors(currentSubProblemId);
    int replacements = 0;

    for (int i = 0; i < permutedNeighborsIndexes.length && !maxReplacementLimitAchieved(replacements); i++) {
      int k = permutedNeighborsIndexes[i];

      double f1 = aggregativeFunction.compute(population.get(k).getObjectives(),
              weightVectorNeighborhood.getWeightVector()[k]);
      double f2 = aggregativeFunction.compute(
              newSolution.getObjectives(),
              weightVectorNeighborhood.getWeightVector()[k]);

      if (f2 < f1) {
        population.set(k, (S) newSolution.copy());
        replacements++;
      }
    }
    return population;
  }

  protected int [] generatePermutationOfhNeighbors(int subProblem) {

    int size = sourceOfNeighborsSize(subProblem);
    int[] permutedArray = new int[size];
    if (neighborType == NeighborType.NEIGHBOR) {
      System.arraycopy(weightVectorNeighborhood.getNeighborhood()[subProblem],0, permutedArray,0,size);
      MOEADUtils.shuffle(permutedArray);
    } else {
      MOEADUtils.randomPermutation(permutedArray, size);
    }
    return permutedArray;
  }


  @Override
  public String getName() {
    return "MOEA/D";
  }

  @Override
  public String getDescription() {
    return "MOEA/D";
  }

  public Map<String, Object> getAlgorithmStatusData() {
    return algorithmStatusData;
  }

  @Override
  public Observable<Map<String, Object>> getObservable() {
    return observable;
  }

  public long getTotalComputingTime() {
    return totalComputingTime;
  }

  public long getEvaluations() {
    return evaluations;
  }

  public MOEAD62<S> setEvaluation(Evaluation<S> evaluation) {
    this.evaluation = evaluation;

    return this;
  }

  public MOEAD62<S> setInitialSolutionsCreation(
      InitialSolutionsCreation<S> initialSolutionsCreation) {
    this.initialSolutionsCreation = initialSolutionsCreation;

    return this;
  }

  protected static class Permutation {
    private int[] permutation;
    private int counter;

    public Permutation(int size) {
      permutation = new int[size];
      MOEADUtils.randomPermutation(permutation, size);
      counter = 0;
    }

    public int getNextElement() {
      int next = permutation[counter];
      counter++;
      if (counter == permutation.length) {
        MOEADUtils.randomPermutation(permutation, permutation.length);
        counter = 0;
      }

      return next;
    }
  }
}